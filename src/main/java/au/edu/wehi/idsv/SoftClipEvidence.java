package au.edu.wehi.idsv;

import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMTag;
import htsjdk.samtools.SamPairUtil.PairOrientation;
import htsjdk.samtools.util.SequenceUtil;

import java.nio.charset.StandardCharsets;

import org.apache.commons.lang3.StringUtils;

import au.edu.wehi.idsv.sam.SAMRecordUtil;

public class SoftClipEvidence implements DirectedEvidence {
	private static final int DOVETAIL_ERROR_MARGIN = 2;
	private static final int MAX_ADAPTER_MAPPED_BASES = 6;
	private final ProcessingContext processContext;
	private final SAMEvidenceSource source;
	private final SAMRecord record;
	private final BreakendSummary location;
	public static SoftClipEvidence create(ProcessingContext processContext, SAMEvidenceSource source, BreakendDirection direction, SAMRecord record) {
		return create(processContext, source, direction, record, null);
	}
	public static SoftClipEvidence create(SoftClipEvidence evidence, SAMRecord realigned) {
		return create(evidence.processContext, evidence.source, evidence.location.direction, evidence.record, realigned);
	}
	public static SoftClipEvidence create(ProcessingContext processContext, SAMEvidenceSource source, BreakendDirection direction, SAMRecord record, SAMRecord realigned) {
		if (record == null) throw new IllegalArgumentException("record is null");
		if (direction == null) throw new IllegalArgumentException("direction is null");
		if (record.getReadUnmappedFlag()) throw new IllegalArgumentException(String.format("record %s is unmapped", record.getReadName()));
		if (record.getReadBases() == null || record.getReadBases() == SAMRecord.NULL_SEQUENCE ) throw new IllegalArgumentException(String.format("record %s missing sequence information", record.getReadName()));
		SoftClipEvidence result = null;
		if (realigned != null && !realigned.getReadUnmappedFlag() && processContext.getRealignmentParameters().realignmentPositionUnique(realigned)) {
			try {
				result = new RealignedSoftClipEvidence(processContext, source, direction, record, realigned);
			} catch (CloneNotSupportedException e) {
				throw new RuntimeException(e);
			}
		} else {
			result = new SoftClipEvidence(processContext, source, direction, record);
		}
		if (result.getSoftClipLength() == 0) {
			throw new IllegalArgumentException(String.format("record %s is not %s soft clipped", record.getReadName(), direction));
		}
		return result;
	}
	protected SoftClipEvidence(ProcessingContext processContext, SAMEvidenceSource source, BreakendDirection direction, SAMRecord record) {
		this.processContext = processContext;
		this.source = source;
		this.record = record;
		int pos = direction == BreakendDirection.Forward ? record.getAlignmentEnd() : record.getAlignmentStart();
		this.location = new BreakendSummary(record.getReferenceIndex(), direction, pos, pos);
	}
	public static int getSoftClipLength(BreakendDirection direction, SAMRecord record) {
		return direction == BreakendDirection.Forward ? SAMRecordUtil.getEndSoftClipLength(record) : SAMRecordUtil.getStartSoftClipLength(record); 
	}
	public static String getEvidenceID(BreakendDirection direction, SAMRecord softClip) {
		// need read name, breakpoint direction & which read in pair
		String readNumber = softClip.getReadPairedFlag() ? softClip.getFirstOfPairFlag() ? "/1" : "/2" : "";
		return String.format("%s%s%s", direction == BreakendDirection.Forward ? "f" : "b", softClip.getReadName(), readNumber);
	}
	@Override
	public String getEvidenceID() {
		return getEvidenceID(location.direction, record);
	}
	@Override
	public BreakendSummary getBreakendSummary() {
		return location;
	}
	@Override
	public byte[] getBreakendSequence() {
		return location.direction == BreakendDirection.Forward ? SAMRecordUtil.getEndSoftClipBases(record) : SAMRecordUtil.getStartSoftClipBases(record);
	}
	@Override
	public byte[] getBreakendQuality() {
		return location.direction == BreakendDirection.Forward ? SAMRecordUtil.getEndSoftClipBaseQualities(record) : SAMRecordUtil.getStartSoftClipBaseQualities(record);
	}
	public SAMRecord getSAMRecord() {
		return this.record;
	}
	public int getSoftClipLength() {
		return getSoftClipLength(location.direction, record); 
	}
	/**
	 * Sequence of untemplated bases
	 * @return
	 */
	public String getUntemplatedSequence() {
		return new String(getBreakendSequence(), StandardCharsets.US_ASCII);
	}
	/**
	 * 0-100 scaled percentage identity of mapped read bases.
	 * @return percentage of reference-aligned bases that match reference 
	 */
	public float getAlignedPercentIdentity() {
		// final byte[] referenceBases = refSeq.get(sequenceDictionary.getSequenceIndex(rec.getReferenceName())).getBases();
        // rec.setAttribute(SAMTag.NM.name(), SequenceUtil.calculateSamNmTag(rec, referenceBases, 0, bisulfiteSequence));
        //if (rec.getBaseQualities() != SAMRecord.NULL_QUALS) {
        // rec.setAttribute(SAMTag.UQ.name(), SequenceUtil.sumQualitiesOfMismatches(rec, referenceBases, 0, bisulfiteSequence));
        Integer nm = record.getIntegerAttribute(SAMTag.NM.name());
		if (nm != null) {
			int refBasesToConsider = record.getReadLength() - SAMRecordUtil.getStartSoftClipLength(record) - SAMRecordUtil.getEndSoftClipLength(record); 
			int refBaseMatches = refBasesToConsider - nm + SequenceUtil.countInsertedBases(record) + SequenceUtil.countDeletedBases(record); 
			return 100.0f * refBaseMatches / (float)refBasesToConsider;
		}
		String md = record.getStringAttribute(SAMTag.MD.name());
		if (StringUtils.isNotEmpty(md)) {
			// Socrates handles this: should we? Which aligners write MD but not NM?
			throw new RuntimeException("Sanity Check Failure: Not Yet Implemented: calculation from reads with MD tag but not NM tag as per Socrates implementation");
		}
		throw new IllegalStateException(String.format("Read %s missing NM tag", record.getReadName()));
	}
	public float getAverageClipQuality() {
		float total = 0;
		byte[] qual = getBreakendQuality();
		if (qual == null) return 0;
		for (int i = 0; i < qual.length; i++) {
			total += qual[i]; 
		}
		return total / qual.length;
	}
	public int getMappingQuality() {
		return record.getMappingQuality();
	}
	public SAMEvidenceSource getEvidenceSource() {
		return source;
	}
	@Override
	public int getLocalMapq() {
		return record.getMappingQuality();
	}
	@Override
	public int getLocalBaseLength() {
		return record.getReadLength() - SAMRecordUtil.getStartSoftClipLength(record) - SAMRecordUtil.getEndSoftClipLength(record);
	}
	@Override
	public int getLocalBaseCount() {
		return getLocalBaseLength();
	}
	@Override
	public int getLocalMaxBaseQual() {
		return SAMRecordUtil.getMaxReferenceBaseQual(record);
	}
	@Override
	public int getLocalTotalBaseQual() {
		return SAMRecordUtil.getTotalReferenceBaseQual(record);
	}
	@Override
	public String toString() {
		return "SoftClip len=" + getSoftClipLength() + " " + getBreakendSummary().toString() + " " + getSAMRecord().getReadName();
	}
	/**
	 * Determines whether this evidence provides support for a putative SV
	 * @param p soft clip parameters
	 * @param rm metrics
	 * @return true if the soft clip provides support, false otherwise
	 */
	public boolean meetsEvidenceCritera(SoftClipParameters p) {
		return getMappingQuality() >= p.minReadMapq
				&& getSoftClipLength() >= p.minLength
				&& getAlignedPercentIdentity() >= p.minAnchorIdentity
				&& !isDovetailing()
				&& !isAdapterSoftClip(p);
	}
	/**
	 * Determine whether this soft clip is cause by read-through into adapter sequence 
	 * @param p soft clip parameter
	 * @return true, if the soft clip is due to adapter sequence, false otherwise
	 */
	public boolean isAdapterSoftClip(SoftClipParameters p) {
		if (p.adapterSequences == null) return false;
		PairOrientation po = source.getMetrics().getPairOrientation();
		if (po == null || po == PairOrientation.FR) {
			// not adapter if the soft clip is on the 5' end of the read
			if (location.direction == BreakendDirection.Forward && record.getReadNegativeStrandFlag()) return false;
			if (location.direction == BreakendDirection.Backward && !record.getReadNegativeStrandFlag()) return false;
			for (String adapter : p.adapterSequences) {
				if (matchesAdapterFR(adapter)) {
					return true;
				}
			}
			return false;
		}
		throw new RuntimeException("Not Yet Implemented: handling of orientations other than Illumina read pair orientation.");
	}
	/**
	 * Checks the soft clip against the given adapter
	 * @param adapter
	 * @return
	 */
	private boolean matchesAdapterFR(String adapter) {
		int scLen = getSoftClipLength();
		if (location.direction == BreakendDirection.Forward) {
			// match soft clip
			for (int i = 0; i <= MAX_ADAPTER_MAPPED_BASES; i++) {
				if (matchesAdapterSequence(adapter, record.getReadBases(), record.getReadLength() - scLen - i, 1, false)) {
					return true;
				}
			}
		} else {
			for (int i = 0; i <= MAX_ADAPTER_MAPPED_BASES; i++) {
				if (matchesAdapterSequence(adapter, record.getReadBases(), scLen + i - 1, -1, true)) {
					return true;
				}
			}
		}
		return false;
	}
	private static boolean matchesAdapterSequence(String adapter, byte[] read, int readStartOffset, int readDirection, boolean complementAdapter) {
		for (int i = 0; readStartOffset + i * readDirection < read.length && readStartOffset + i * readDirection  >= 0 && i < adapter.length(); i++) {
			byte readBase = read[readStartOffset + i * readDirection];
			byte adapterBase = (byte)adapter.charAt(i);
			if (complementAdapter) adapterBase = SequenceUtil.complement(adapterBase);
			if (SequenceUtil.isValidBase(readBase) && readBase != adapterBase) {
				return false;
			}
		}
		return true;
	}
	/**
	 * Dovetailing reads do not support SVs, they are caused by fragment size less than read length
	 * 
	 *     =======>
	 *  <=======
	 * 
	 * 
	 * @param expectedOrientation read pair orientation
	 * @return true if the soft clip is due to a fragment size smaller than the read length
	 */
	public boolean isDovetailing() {
		if (!record.getReadPairedFlag() || record.getMateUnmappedFlag()) return false;
		PairOrientation po = source.getMetrics().getPairOrientation();
		if (po == null || po == PairOrientation.FR) {
			return record.getMateReferenceIndex() == record.getReferenceIndex()
					&& Math.abs(record.getAlignmentStart() - record.getMateAlignmentStart()) <= DOVETAIL_ERROR_MARGIN
					// dovetails happen on the 3' end of the read for FR 
					&& ((location.direction == BreakendDirection.Forward && !record.getReadNegativeStrandFlag())
						|| (location.direction == BreakendDirection.Backward && record.getReadNegativeStrandFlag()));
		}
		throw new RuntimeException("Not Yet Implemented: handling of orientations other than Illumina read pair orientation.");
	}
}
