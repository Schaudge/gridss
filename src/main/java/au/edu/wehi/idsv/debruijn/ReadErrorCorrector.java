package au.edu.wehi.idsv.debruijn;

import au.edu.wehi.idsv.BreakendDirection;
import au.edu.wehi.idsv.DirectedEvidence;
import au.edu.wehi.idsv.NonReferenceReadPair;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.SequenceUtil;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.util.HashSet;
import java.util.Set;

public class ReadErrorCorrector {
    private static long SENTINEL_VALUE = Long.MIN_VALUE;
    private static int MAX_BASE_CORRECTIONS = 2;
    private static final Log log = Log.getInstance(ReadErrorCorrector.class);
    private final Long2IntMap kmerCounts = new Long2IntOpenHashMap();
    private final int k;
    private final float collapseMultiple;
    private Long2LongMap collapseLookup;
    /**
     * Performance optimisation of KmerEncodingHelper.neighbouringStates()
     * Since we're just XORing with the state to find the neighbours,
     * we can create a lookup of XOR patterns by precomputing the pattern
     */
    private final long[] neighbourXORLookup;
    /**
     * Performance optimisation: no need to do neighbour lookups for kmers
     * that we won't be able to collapse anyway
     */
    private int maxCount = 0;

    public ReadErrorCorrector(int k, float collapseMultiple) {
        if (k > 31) throw new IllegalArgumentException("k cannot exceed 31");
        this.k = k;
        this.collapseMultiple = collapseMultiple;
        this.neighbourXORLookup = KmerEncodingHelper.neighbouringStates(k, 0L);
    }

    public static void errorCorrect(int k, float collapseMultiple, Iterable<? extends DirectedEvidence> evidence) {
        ReadErrorCorrector ec = new ReadErrorCorrector(k, collapseMultiple);
        // need to deduplicate the underlying reads so we don't double count
        // kmers from reads with multiple evidence (e.g. multiple indels or SC on both ends)
        Set<SAMRecord> reads = new HashSet<>();
        Set<SAMRecord> rcreads = new HashSet<>();
        for (DirectedEvidence de : evidence) {
            reads.add(de.getUnderlyingSAMRecord());
            if (de instanceof NonReferenceReadPair) {
                SAMRecord mate = ((NonReferenceReadPair) de).getNonReferenceRead();
                if ((de.getBreakendSummary().direction == BreakendDirection.Forward) ^ mate.getReadNegativeStrandFlag()) {
                    rcreads.add(mate);
                } else {
                    reads.add(mate);
                }
            }
        }
        reads.stream().forEach(r -> ec.countKmers(r, false));
        rcreads.stream().forEach(r -> ec.countKmers(r, true));
        reads.stream().forEach(r -> ec.errorCorrect(r, false));
        rcreads.stream().forEach(r -> ec.errorCorrect(r, true));
    }

    public void countKmers(SAMRecord r, boolean reverseComplement) {
        PackedSequence ps = new PackedSequence(r.getReadBases(), reverseComplement, reverseComplement);
        for (int i = 0; i < ps.length() - k + 1; i++) {
            long kmer = ps.getKmer(i, k);
            int count = kmerCounts.get(kmer);
            count++;
            kmerCounts.put(kmer, count);
            if (count > maxCount) {
                maxCount = count;
            }
        }
        collapseLookup = null;
    }
    public int errorCorrect(SAMRecord r, boolean reverseComplement) {
        if (r.getReadLength() < k) return 0;
        ensureCollapseLookup();
        PackedSequence ps = new PackedSequence(r.getReadBases(), reverseComplement, reverseComplement);
        int changes = error_correct(r, ps);
        if (changes > 0) {
            byte[] seq = ps.getBytes(0, r.getReadBases().length);
            if (reverseComplement) {
                SequenceUtil.reverseComplement(seq);
            }
            r.setReadBases(seq);
        }
        return changes;
    }
    public int error_correct(SAMRecord r, PackedSequence ps) {
        int changes = 0;
        while (error_correct_flanking_kmers(r, ps) || error_correct_start(r, ps) || error_correct_end(r, ps)) {
            changes++;
            if (changes >= MAX_BASE_CORRECTIONS) {
                return changes;
            }
        }
        return changes;
    }
    private boolean error_correct_flanking_kmers(SAMRecord r, PackedSequence ps) {
        for (int i = 1; i < ps.length() - k + 1; i++) {
            long leftKmer = ps.getKmer(i - 1, k);
            long leftTransform = collapseLookup.getOrDefault(leftKmer, SENTINEL_VALUE);
            if (leftTransform != SENTINEL_VALUE) {
                long rightKmer = ps.getKmer(i + 1, k);
                long rightTransform = collapseLookup.getOrDefault(rightKmer, SENTINEL_VALUE);
                if (rightTransform != SENTINEL_VALUE) {
                    // check they both agree on the base change being made
                    long basesMatching = KmerEncodingHelper.basesMatching(k - 2, leftTransform, rightTransform >> 4);
                    if (basesMatching == k - 2) {
                        ps.setKmer(leftTransform, i - 1, k);
                        return true;
                    }
                }
            }
        }
        return false;
    }
    private boolean error_correct_start(SAMRecord r, PackedSequence ps) {
        long kmer = ps.getKmer(0, k);
        long transform = collapseLookup.getOrDefault(kmer, SENTINEL_VALUE);
        if (transform != SENTINEL_VALUE) {
            // check we want to change the first base
            int basesDifferent = KmerEncodingHelper.basesDifference(k - 2, kmer, transform);
            boolean lowBytesSame = ((kmer & 3) != (transform & 3));
            if (basesDifferent == 0) {
                ps.setKmer(transform, 0, k);
                return true;
            }
        }
        return false;
    }
    private boolean error_correct_end(SAMRecord r, PackedSequence ps) {
        long kmer = ps.getKmer(ps.length() - k, k);
        long transform = collapseLookup.getOrDefault(kmer, SENTINEL_VALUE);
        if (transform != SENTINEL_VALUE) {
            // check we want to change the last base
            if ((kmer & 15) != (transform & 15)) {
                ps.setKmer(transform, ps.length() - k, k);
                return true;
            }
        }
        return false;
    }

    private void ensureCollapseLookup() {
        if (collapseLookup == null) {
            collapseLookup = createCollapseLookup();
            log.debug(String.format("Collapsed %d of %d kmer.", collapseLookup.size(), kmerCounts.size()));
        }
    }
    private Long2LongOpenHashMap createCollapseLookup() {
        Long2LongOpenHashMap lookup = new Long2LongOpenHashMap();
        int maxCollapseCount = (int)Math.floor(maxCount / collapseMultiple);
        for (long kmer : kmerCounts.keySet()) {
            int count = kmerCounts.get(kmer);
            if (count <= maxCollapseCount) { // don't both with kmers we know we can't collapse
                long bestNeighbourKmer = bestNeighbour(kmer);
                int bestNeighbourCount = kmerCounts.get(bestNeighbourKmer);
                if (count * collapseMultiple <= bestNeighbourCount) {
                    lookup.put(kmer, bestNeighbourKmer);
                }
            }
        }
        return lookup;
    }

    /**
     * Neighbour (hamming distance = 1) kmer with highest kmer count
     * @param kmer kmer to check neighbours of
     * @return kmer of highest neighbour. Returns the input kmer if all neighbours have 0 kmer counts.
     */
    private long bestNeighbour(long kmer) {
        long bestKmer = kmer;
        int bestCount = 0;
        for (long neighbourXOR : neighbourXORLookup) {
            long neighbour = kmer ^ neighbourXOR;
            int count = kmerCounts.get(neighbour);
            if (count > bestCount) {
                bestKmer = neighbour;
                bestCount = count;
            }
        }
        return bestKmer;
    }
}
