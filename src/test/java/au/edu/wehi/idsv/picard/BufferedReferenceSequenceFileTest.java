package au.edu.wehi.idsv.picard;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.stream.Collectors;

import org.junit.Test;

import au.edu.wehi.idsv.TestHelper;
import au.edu.wehi.idsv.picard.BufferedReferenceSequenceFile;


public class BufferedReferenceSequenceFileTest extends TestHelper {
	@Test
	public void getSequenceShouldMatchUnderlying() throws IOException {
		BufferedReferenceSequenceFile b = new BufferedReferenceSequenceFile(SMALL_FA);
		for (String contig : SMALL_FA.getSequenceDictionary().getSequences().stream().map(ssr -> ssr.getSequenceName()).collect(Collectors.toList())) {
			assertEquals(S(b.getSequence(contig).getBases()), S(SMALL_FA.getSequence(contig).getBases()));
		}
	}
	@Test
	public void getSubsequenceAtShouldMatchUnderlying() throws IOException {
		BufferedReferenceSequenceFile b = new BufferedReferenceSequenceFile(SMALL_FA);
		for (String contig : SMALL_FA.getSequenceDictionary().getSequences().stream().map(ssr -> ssr.getSequenceName()).collect(Collectors.toList())) {
			for (int i = 1; i < 100; i++) {
				for (int j = i; j < 100; j++) {
					assertEquals(S(b.getSubsequenceAt(contig, i, j).getBases()), S(SMALL_FA.getSubsequenceAt(contig, i, j).getBases()));
					assertEquals(b.getSubsequenceAt(contig, i, j).getName(), SMALL_FA.getSubsequenceAt(contig, i, j).getName());
					assertEquals(b.getSubsequenceAt(contig, i, j).getContigIndex(), SMALL_FA.getSubsequenceAt(contig, i, j).getContigIndex());
				}
			}
		}
	}
}
