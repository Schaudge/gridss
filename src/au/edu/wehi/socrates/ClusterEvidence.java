package au.edu.wehi.socrates;

import java.io.File;
import java.io.IOException;

import net.sf.picard.cmdline.CommandLineProgram;
import net.sf.picard.cmdline.Option;
import net.sf.picard.cmdline.StandardOptionDefinitions;
import net.sf.picard.cmdline.Usage;
import net.sf.picard.io.IoUtil;
import net.sf.picard.util.Log;
import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.SAMFileReader;
import net.sf.samtools.SAMSequenceDictionary;

import org.broadinstitute.variant.variantcontext.writer.VariantContextWriter;
import org.broadinstitute.variant.variantcontext.writer.VariantContextWriterFactory;

/**
 * Clusters evidence that supports a common breakpoint together
 * @author Daniel Cameron
 *
 */
public class ClusterEvidence extends CommandLineProgram {

    private static final String PROGRAM_VERSION = "0.1";

    // The following attributes define the command-line arguments
    @Usage
    public String USAGE = getStandardUsagePreamble() + "Calls breakpoints between the two given chromosomes" +
    		"based on the evidence provided" + PROGRAM_VERSION;
    @Option(doc = "Realigned breakpoint BAM file",
            optional = false,
            shortName = "R1")
    public File REALIGN_INPUT1;
    @Option(doc = "Coordinate sorted input file containing reads supporting putative structural variations",
            optional = false,
            shortName = "SV1")
    public File SV_READ_INPUT1;
    @Option(doc = "DP and OEA read pairs sorted by coordinate of mapped mate read.",
            optional = false,
            shortName = "MATE1")
    public File MATE_COORDINATE_INPUT1 = null;
    @Option(doc = "Directed single-ended breakpoints.",
            optional = false,
            shortName= "VCF1")
    public File VCF_INPUT1;
    @Option(doc = "Realigned breakpoint BAM file",
            optional = false,
            shortName = "R2")
    public File REALIGN_INPUT2;
    @Option(doc = "Coordinate sorted input file containing reads supporting putative structural variations",
            optional = false,
            shortName = "SV2")
    public File SV_READ_INPUT2;
    @Option(doc = "DP and OEA read pairs sorted by coordinate of mapped mate read.",
            optional = false,
            shortName = "MATE2")
    public File MATE_COORDINATE_INPUT2 = null;
    @Option(doc = "Directed single-ended breakpoints.",
            optional = false,
            shortName= "VCF2")
    public File VCF_INPUT2;
    @Option(doc = "Breakpoint calls in VCF format",
            optional = false,
            shortName= StandardOptionDefinitions.OUTPUT_SHORT_NAME)
    public File OUTPUT;
    @Option(doc = "Picard metrics file generated by ExtractEvidence",
            optional = true)
    public File METRICS = null;
    private Log log = Log.getInstance(ClusterEvidence.class);
    @Override
	protected int doWork() {
    	SAMFileReader.setDefaultValidationStringency(SAMFileReader.ValidationStringency.SILENT);
    	try {
    		if (METRICS == null) {
    			METRICS = FileNamingConvention.getMetrics(SV_READ_INPUT1);
    		}
    		IoUtil.assertFileIsReadable(METRICS);
    		IoUtil.assertFileIsReadable(REALIGN_INPUT1);
    		IoUtil.assertFileIsReadable(SV_READ_INPUT1);
    		IoUtil.assertFileIsReadable(MATE_COORDINATE_INPUT1);
    		IoUtil.assertFileIsReadable(VCF_INPUT1);
    		IoUtil.assertFileIsReadable(REALIGN_INPUT2);
    		IoUtil.assertFileIsReadable(SV_READ_INPUT2);
    		IoUtil.assertFileIsReadable(MATE_COORDINATE_INPUT2);
    		IoUtil.assertFileIsReadable(VCF_INPUT2);
    		
    		final RelevantMetrics metrics = new RelevantMetrics(METRICS);
    		
    		//final ProgressLogger progress = new ProgressLogger(log);
	    	final SAMFileReader reader = new SAMFileReader(INPUT);
	    	final SAMFileReader mateReader = new SAMFileReader(MATE_COORDINATE_INPUT);
	    	final SAMFileHeader header = reader.getFileHeader();
	    	final SAMSequenceDictionary dictionary = header.getSequenceDictionary();
	    	
			final VariantContextWriter vcfWriter = VariantContextWriterFactory.create(VCF_OUTPUT, dictionary);
    	} catch (IOException e) {
    		log.error(e);
    		throw new RuntimeException(e);
    	}
        return 0;
    }
	public static void main(String[] argv) {
        System.exit(new GenerateDirectedBreakpoints().instanceMain(argv));
    }
}
