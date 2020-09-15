package gridss;

import au.edu.wehi.idsv.picard.ReferenceLookup;
import au.edu.wehi.idsv.sam.ChimericAlignment;
import au.edu.wehi.idsv.vcf.SvType;
import au.edu.wehi.idsv.vcf.VcfInfoAttributes;
import au.edu.wehi.idsv.vcf.VcfSvConstants;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;
import gridss.cmdline.ReferenceCommandLineProgram;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.SequenceUtil;
import htsjdk.tribble.annotation.Strand;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.variantcontext.VariantContextComparator;
import htsjdk.variant.variantcontext.VariantContextUtils;
import htsjdk.variant.variantcontext.writer.Options;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.variantcontext.writer.VariantContextWriterBuilder;
import htsjdk.variant.vcf.VCFFileReader;
import htsjdk.variant.vcf.VCFHeader;
import joptsimple.internal.Strings;
import org.apache.commons.compress.utils.Lists;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import picard.cmdline.StandardOptionDefinitions;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@CommandLineProgramProperties(
        summary = "Filters an annotated VIRUSBreakend VCF to only likely integration sites",
        oneLineSummary = "Filters an annotated VIRUSBreakend VCF to only likely integration sites",
        programGroup = gridss.cmdline.programgroups.VariantCalling.class
)
public class VirusBreakendFilter extends ReferenceCommandLineProgram {
	private static final Log log = Log.getInstance(VirusBreakendFilter.class);

	@Argument(shortName = StandardOptionDefinitions.INPUT_SHORT_NAME, doc = "VIRUSBreakend VCF file to filter")
	public File INPUT;
	@Argument(shortName = StandardOptionDefinitions.OUTPUT_SHORT_NAME, doc = "Filtered VCF")
	public File OUTPUT;
	@Argument(doc = "Minimum portion of host alignment that does not match a simple or low complexity repeat")
	public double MINIMUM_REPEAT_OVERLAP = 0.5;
	@Argument(doc = "Minimum portion of breakend sequence that aligns to host genome")
	public double MINIMUM_HOST_OVERLAP = 0.5;

	public static void main(String[] argv) {
        System.exit(new VirusBreakendFilter().instanceMain(argv));
    }

	@Override
	protected int doWork() {
		IOUtil.assertFileIsReadable(INPUT);
		IOUtil.assertFileIsWritable(OUTPUT);
		ReferenceLookup hostRef = getReference();
		if (hostRef == null) throw new IllegalArgumentException("Missing host REFERENCE_SEQUENCE");
		SAMSequenceDictionary dictionary = getReference().getSequenceDictionary();
		if (dictionary == null) throw new IllegalArgumentException("Missing .dict file for host genome");
		try (VCFFileReader vcfReader = new VCFFileReader(INPUT, false)) {
			VCFHeader header = vcfReader.getFileHeader();
			SAMSequenceDictionary virusDict = header.getSequenceDictionary();
			for (SAMSequenceRecord seq : virusDict.getSequences()) {
				dictionary.addSequence(seq);
			}
			VariantContextWriterBuilder builder = new VariantContextWriterBuilder()
					.setReferenceDictionary(dictionary)
					.setOutputFile(OUTPUT);
			List<VariantContext> output = vcfReader.iterator()
					.stream()
					.filter(vc -> shouldKeep(vc))
					.flatMap(vc -> transformToBreakpointNotation(dictionary, vc).stream())
					.sorted(new VariantContextComparator(dictionary))
					.collect(Collectors.toList());
			try (VariantContextWriter vcfWriter = builder.build()) {
				header.setSequenceDictionary(dictionary);
				vcfWriter.writeHeader(header);
				for (VariantContext vc : output) {
					vcfWriter.add(vc);
				}
			}
		}
		return 0;
	}

	public static List<VariantContext> transformToBreakpointNotation(SAMSequenceDictionary dictionary, VariantContext vc) {
		List<ChimericAlignment> bealn = infoToChimeric(vc, VcfInfoAttributes.BREAKEND_ALIGNMENTS.attribute());
		bealn.sort(ChimericAlignment.ByMapqAlignedLength);
		ChimericAlignment humanAlignment = bealn.get(0);
		boolean viralNegative = vc.getAlternateAllele(0).getDisplayString().startsWith(".");
		boolean breakpointAtEndOfHostAlignment = viralNegative != humanAlignment.isNegativeStrand;
		int hostPosition = humanAlignment.pos + (breakpointAtEndOfHostAlignment ? humanAlignment.cigar.getReferenceLength() - 1 : 0);
		String hostId = vc.getID() + "_host";
		String virusId = vc.getID() + "_virus";
		String alt = vc.getAlternateAllele(0).getDisplayString();
		String breakendSeq = alt.substring(1, alt.length() - 1);
		char anchorBase = alt.charAt(0) == '.' ? alt.charAt(alt.length() - 1) : alt.charAt(0);
		String virusInsertedSequence;
		if (!viralNegative) {
			// virus - insertion - host
			// NIIIHHHHSSS.
			int insertLength = humanAlignment.getFirstAlignedBaseReadOffset();
			virusInsertedSequence = breakendSeq.substring(0, insertLength);
		} else {
			// virus - insertion - host
			// ClipHostInsert.
			int insertOffset = humanAlignment.getLastAlignedBaseReadOffset();
			virusInsertedSequence = breakendSeq.substring(insertOffset + 1);
		}
		int viralPosition = vc.getStart();
		Strand virusBreakpointOrientation = viralNegative ? Strand.NEGATIVE : Strand.POSITIVE;
		Strand hostBreakpointOrientation = breakpointAtEndOfHostAlignment ? Strand.POSITIVE : Strand.NEGATIVE;
		String hostChr = humanAlignment.rname;
		String viralChr = vc.getContig();
		String hostInsertedSequence = virusBreakpointOrientation == hostBreakpointOrientation ? SequenceUtil.reverseComplement(virusInsertedSequence) : virusInsertedSequence;
		VariantContextBuilder hostBuilder = new VariantContextBuilder()
				.chr(humanAlignment.rname)
				.start(hostPosition)
				.stop(hostPosition)
				.id(hostId)
				.log10PError(vc.getLog10PError())
				.filters(vc.getFilters())
				.alleles("N",
						(hostBreakpointOrientation == Strand.FORWARD ? "N" + hostInsertedSequence : "") +
						(virusBreakpointOrientation == Strand.FORWARD ? "]" : "[") +
						viralChr + ":" + viralPosition +
						(virusBreakpointOrientation == Strand.FORWARD ? "]" : "[") +
						(hostBreakpointOrientation == Strand.FORWARD ? "" : hostInsertedSequence + "N")
						);
		VariantContextBuilder virusBuilder = new VariantContextBuilder(vc)
				.id(virusId)
				.alleles(String.valueOf(anchorBase),
						(virusBreakpointOrientation == Strand.FORWARD ? anchorBase + virusInsertedSequence : "") +
						(hostBreakpointOrientation == Strand.FORWARD ? "]" : "[") +
						hostChr + ":" + hostPosition +
						(hostBreakpointOrientation == Strand.FORWARD ? "]" : "[") +
						(virusBreakpointOrientation == Strand.FORWARD ? "" : virusInsertedSequence + anchorBase)
				);
		for (VcfInfoAttributes attr : new VcfInfoAttributes[] {
				VcfInfoAttributes.BREAKEND_ALIGNMENTS,
				VcfInfoAttributes.INSERTED_SEQUENCE_NCBI_TAXONOMY_ID,
				VcfInfoAttributes.INSERTED_SEQUENCE_REPEATMASKER_SA_TAG,
				VcfInfoAttributes.INSERTED_SEQUENCE_REPEATMASKER_OVERLAP,
				VcfInfoAttributes.INSERTED_SEQUENCE_REPEATMASKER_REPEAT_TYPE,
				VcfInfoAttributes.INSERTED_SEQUENCE_REPEATMASKER_REPEAT_CLASS,
				VcfInfoAttributes.INSERTED_SEQUENCE_REPEATMASKER_ORIENTATION,
		}) {
			if (vc.hasAttribute(attr.attribute())) {
				hostBuilder.attribute(attr.attribute(), vc.getAttribute(attr.attribute()));
			}
		}
		hostBuilder.attribute(VcfSvConstants.BREAKEND_EVENT_ID_KEY, vc.getID());
		virusBuilder.attribute(VcfSvConstants.BREAKEND_EVENT_ID_KEY, vc.getID());
		hostBuilder.attribute(VcfSvConstants.MATE_BREAKEND_ID_KEY, virusBuilder.getID());
		virusBuilder.attribute(VcfSvConstants.MATE_BREAKEND_ID_KEY, hostBuilder.getID());
		hostBuilder.attribute(VcfSvConstants.SV_TYPE_KEY, SvType.BND.name());
		virusBuilder.attribute(VcfSvConstants.SV_TYPE_KEY, SvType.BND.name());
		return ImmutableList.of(virusBuilder.make(), hostBuilder.make());
	}

	private boolean shouldKeep(VariantContext vc) {
		String alt = vc.getAlternateAllele(0).getDisplayString();
		//if (alt.contains("kraken")) return true;
		// single breakends only
		if (!alt.contains(".")) return false;
		int breakendLength = alt.length() - 2;
		List<ChimericAlignment> host = infoToChimeric(vc, VcfInfoAttributes.BREAKEND_ALIGNMENTS.attribute());
		RangeSet<Integer> repeats = repeatRanges(vc);
		for (ChimericAlignment hca : host) {
			int hostAlignmentLength = hca.getLastAlignedBaseReadOffset() - hca.getFirstAlignedBaseReadOffset() + 1;
			if ((hostAlignmentLength / (double)breakendLength) < MINIMUM_HOST_OVERLAP) continue;
			RangeSet<Integer> overlappingRepeats = repeats.subRangeSet(Range.openClosed(hca.getFirstAlignedBaseReadOffset(), hca.getLastAlignedBaseReadOffset() + 1));
			int overlap = overlappingRepeats.asRanges().stream().mapToInt(r -> r.upperEndpoint() - r.lowerEndpoint()).sum();
			if (overlap / (double)hostAlignmentLength < MINIMUM_REPEAT_OVERLAP) return true;
		}
		return false;
	}
	private static List<ChimericAlignment> infoToChimeric(VariantContext vc, String tag) {
		if (Strings.isNullOrEmpty(tag)) return Collections.emptyList();
		List<String> list = vc.getAttributeAsStringList(tag, "");
		if (list.size() == 0 || Strings.isNullOrEmpty(list.get(0))) Collections.emptyList();
		List<ChimericAlignment> calist = list.stream()
				.map(aln -> new ChimericAlignment(aln, "[|:]"))
				.collect(Collectors.toList());
		return calist;
	}
	// half-open
	private static RangeSet<Integer> repeatRanges(VariantContext vc) {
		RangeSet<Integer> rs = TreeRangeSet.create();
		for (ChimericAlignment ca : infoToChimeric(vc, VcfInfoAttributes.INSERTED_SEQUENCE_REPEATMASKER_SA_TAG.attribute())) {
			if (ca.rname.contains("Simple_repeat") || ca.rname.contains("Low_complexity")) {
				rs.add(Range.closedOpen(ca.getFirstAlignedBaseReadOffset(), ca.getLastAlignedBaseReadOffset() + 1));
			}
		}
		return rs;
	}
}