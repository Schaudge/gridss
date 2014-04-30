package au.edu.wehi.socrates;

import java.io.File;
import java.io.IOException;

import net.sf.picard.cmdline.CommandLineProgram;
import net.sf.picard.cmdline.Option;
import net.sf.picard.cmdline.StandardOptionDefinitions;
import net.sf.picard.fastq.FastqRecord;
import net.sf.picard.fastq.FastqWriter;
import net.sf.picard.fastq.FastqWriterFactory;
import net.sf.picard.io.IoUtil;
import net.sf.picard.reference.ReferenceSequenceFile;
import net.sf.picard.reference.ReferenceSequenceFileFactory;
import net.sf.picard.util.Log;
import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.SAMFileReader;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMSequenceDictionary;

import org.broadinstitute.variant.variantcontext.writer.VariantContextWriter;
import org.broadinstitute.variant.variantcontext.writer.VariantContextWriterBuilder;
import org.broadinstitute.variant.vcf.VCFHeader;

import au.edu.wehi.socrates.debruijn.DeBruijnAssembler;
import au.edu.wehi.socrates.vcf.VcfConstants;

import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;

public class GenerateDirectedBreakpoints extends CommandLineProgram {

    private static final String PROGRAM_VERSION = "0.1";

    @Option(doc = "Coordinate sorted input file containing reads supporting putative structural variations",
            optional = false,
            shortName = "SV")
    public File SV_INPUT;
    @Option(doc = "DP and OEA read pairs sorted by coordinate of mapped mate read.",
            optional = false,
            shortName = "MCI")
    public File MATE_COORDINATE_INPUT;
    @Option(doc = "Directed single-ended breakpoints. A placeholder contig is output as the breakpoint partner.",
            optional = false,
            shortName=StandardOptionDefinitions.OUTPUT_SHORT_NAME)
    public File VCF_OUTPUT;
    @Option(doc = "FASTQ of reference strand sequences of putative breakpoints excluding anchored bases. These sequences are used to align breakpoints",
            optional = false,
            shortName = "FQ")
    public File FASTQ_OUTPUT;
    @Option(doc = "Picard metrics file generated by ExtractEvidence",
            optional = true)
    public File METRICS = null;
    @Option(doc = "Reference used for alignment",
            optional = false)
    public File REFERENCE;
    @Option(doc = "Minimum alignment mapq",
    		optional=true)
    public int MIN_MAPQ = 5;
    @Option(doc = "Length threshold of long soft-clip",
    		optional=true)
    public int LONG_SC_LEN = 25;
    @Option(doc = "Minimum alignment percent identity to reference. Takes values in the range 0-100.",
    		optional=true)
    public float MIN_PERCENT_IDENTITY = 95;
    @Option(doc = "Minimum average base quality score of soft clipped sequence",
    		optional=true)
    public float MIN_LONG_SC_BASE_QUALITY = 5;
    @Option(doc = "k-mer used for de bruijn graph construction",
    		optional=true,
    		shortName="K")
    public int KMER = 25;
    private Log log = Log.getInstance(GenerateDirectedBreakpoints.class);
    @Override
	protected int doWork() {
    	SAMFileReader.setDefaultValidationStringency(SAMFileReader.ValidationStringency.SILENT);
    	try {
    		if (METRICS == null) {
    			METRICS = FileNamingConvention.getMetrics(SV_INPUT);
    		}
    		IoUtil.assertFileIsReadable(METRICS);
    		IoUtil.assertFileIsReadable(SV_INPUT);
    		IoUtil.assertFileIsReadable(MATE_COORDINATE_INPUT);
    		
    		IoUtil.assertFileIsWritable(VCF_OUTPUT);
    		IoUtil.assertFileIsWritable(FASTQ_OUTPUT);
    		
    		//final ProgressLogger progress = new ProgressLogger(log);
	    	final SAMFileReader reader = new SAMFileReader(SV_INPUT);
	    	final SAMFileReader mateReader = new SAMFileReader(MATE_COORDINATE_INPUT);
	    	final SAMFileHeader header = reader.getFileHeader();
	    	final SAMSequenceDictionary dictionary = header.getSequenceDictionary();
	    	final RelevantMetrics metrics = new RelevantMetrics(METRICS);
	    	final ReferenceSequenceFile reference = ReferenceSequenceFileFactory.getReferenceSequenceFile(REFERENCE);
	    	final ProcessingContext processContext = new ProcessingContext(reference, dictionary, metrics);
	    	
			final PeekingIterator<SAMRecord> iter = Iterators.peekingIterator(reader.iterator());
			final PeekingIterator<SAMRecord> mateIter = Iterators.peekingIterator(mateReader.iterator());
			final FastqWriter fastqWriter = new FastqWriterFactory().newWriter(FASTQ_OUTPUT);
			final VariantContextWriter vcfWriter = new VariantContextWriterBuilder()
				.setOutputFile(VCF_OUTPUT)
				.setReferenceDictionary(dictionary)
				.build();
			final VCFHeader vcfHeader = new VCFHeader();
			VcfConstants.addHeaders(vcfHeader);
			vcfWriter.writeHeader(vcfHeader);
			
			DirectedEvidenceIterator dei = new DirectedEvidenceIterator(processContext, iter, mateIter, null, null);
			ReadEvidenceAssembler assembler = new DeBruijnAssembler(processContext, KMER);
			while (dei.hasNext()) {
				DirectedEvidence readEvidence = dei.next();
				if (readEvidence instanceof SoftClipEvidence) {
					SoftClipEvidence sce = (SoftClipEvidence)readEvidence;
					if (sce.getMappingQuality() > MIN_MAPQ &&
							sce.getSoftClipLength() > LONG_SC_LEN &&
							sce.getAlignedPercentIdentity() > MIN_PERCENT_IDENTITY &&
							sce.getAverageClipQuality() > MIN_LONG_SC_BASE_QUALITY) {
						FastqRecord fastq = BreakpointFastqEncoding.getRealignmentFastq(sce);
						fastqWriter.write(fastq);
					}
				}
				processAssemblyEvidence(assembler.addEvidence(readEvidence), fastqWriter, vcfWriter);
			}
			processAssemblyEvidence(assembler.endOfEvidence(), fastqWriter, vcfWriter);
	    	fastqWriter.close();
	    	vcfWriter.close();
	    	reader.close();
	    	mateReader.close();
    	} catch (IOException e) {
    		log.error(e);
    		throw new RuntimeException(e);
    	}
        return 0;
    }
    private void processAssemblyEvidence(Iterable<DirectedBreakpointAssembly> evidenceList, FastqWriter fastqWriter, VariantContextWriter vcfWriter) {
    	if (evidenceList != null) {
	    	for (DirectedBreakpointAssembly a : evidenceList) {
	    		if (a != null) {
		    		FastqRecord fastq = BreakpointFastqEncoding.getRealignmentFastq(a);
		    		fastqWriter.write(fastq);
		    		vcfWriter.add(a);
	    		}
	    	}
    	}
    }
	public static void main(String[] argv) {
        System.exit(new GenerateDirectedBreakpoints().instanceMain(argv));
    }
}
