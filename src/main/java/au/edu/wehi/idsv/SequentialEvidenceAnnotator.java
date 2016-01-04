package au.edu.wehi.idsv;

import htsjdk.samtools.SAMRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import au.edu.wehi.idsv.vcf.VcfSvConstants;
import au.edu.wehi.idsv.visualisation.TrackedBuffer;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Ordering;
import com.google.common.collect.PeekingIterator;

/**
 * Annotates sorted breakends with the evidence supporting the call
 * 
 * @author Daniel Cameron
 *
 */
public class SequentialEvidenceAnnotator extends AbstractIterator<VariantContextDirectedEvidence> implements BreakendAnnotator, TrackedBuffer {
	private final ProcessingContext context;
	private final int maxCallRange;
	private final boolean assignEvidenceToSingleBreakpoint;
	private final PeekingIterator<? extends DirectedEvidence> evidenceIt;
	private final Iterator<? extends VariantContextDirectedEvidence> callIt;
	private final ArrayDeque<ActiveVariant> variantBuffer = new ArrayDeque<ActiveVariant>();
	private final Map<String, ActiveVariant> bufferedVariantId = new HashMap<String, ActiveVariant>();
	private final EvidenceToCsv dump;
	private class ActiveVariant {
		public final String id;
		public final String mateid;
		public final String eventid;
		public final long startLocation;
		//public final long endLocation;
		public final BreakendSummary location;
		public final float score;
		private final StructuralVariationCallBuilder builder;
		private List<DirectedEvidence> evidenceDump;
		public ActiveVariant(VariantContextDirectedEvidence call) {
			this.id = call.hasID() ? call.getID() : null;
			this.mateid = call.hasID() ? (String)call.getAttribute(VcfSvConstants.MATE_BREAKEND_ID_KEY, null) : null;
			this.eventid = (String)call.getAttribute(VcfSvConstants.BREAKEND_EVENT_ID_KEY, null);
			this.builder = new StructuralVariationCallBuilder(context, call);
			this.score = (float)call.getPhredScaledQual();
			assert(this.score >= 0); // variant must have score set
			this.location = call.getBreakendSummary();
			this.startLocation = context.getLinear().getStartLinearCoordinate(this.location);
			//this.endLocation = context.getLinear().getEndLinearCoordinate(this.location);
			if (dump != null) {
				evidenceDump = new ArrayList<DirectedEvidence>();
			}
		}
		public void attributeEvidence(DirectedEvidence e) {
			if (evidenceDump != null) {
				evidenceDump.add(e);
			}
			builder.addEvidence(e);
		}
		public VariantContextDirectedEvidence callVariant() {
			VariantContextDirectedEvidence call = builder.make();
			if (dump != null) {
				for (DirectedEvidence e : evidenceDump) {
					dump.writeEvidence(e, call);
				}
			}
			return call;
		}
		public String toString() {
			return String.format("%s %f %s", location, score, id);
		}
	}
	/**
	 * Orders variants by their score then position
	 * Positional comparison that returns the same order for both high and low breakends
	 * is required to ensure both sides of paired evidence is assigned to corresponding
	 * breakends of the same event. 
	 */
	private static final Ordering<ActiveVariant> ByScoreAscPositionDesc = new Ordering<ActiveVariant>() {
		public int compare(ActiveVariant o1, ActiveVariant o2) {
			ComparisonChain chain = ComparisonChain.start()
			        .compare(o1.score, o2.score);
			if (o1.location instanceof BreakpointSummary && o2.location instanceof BreakpointSummary) {
				chain = chain.compare((BreakpointSummary)o2.location, (BreakpointSummary)o1.location, BreakpointSummary.ByLowHigh);
			} else {
				chain = chain.compare(o2.location, o1.location, BreakendSummary.ByStartEnd);
			}
			chain = chain
			        .compare(o1.eventid, o2.eventid)
			        .compare(o1.id, o2.id);
			return chain.result();
		}
	};
	public SequentialEvidenceAnnotator(
			ProcessingContext context,
			Iterator<? extends VariantContextDirectedEvidence> calls,
			Iterator<? extends DirectedEvidence> evidence,
			int maxCallWindowSize,
			boolean assignEvidenceToSingleBreakpoint,
			EvidenceToCsv dump) {
		this.context = context;
		this.maxCallRange = maxCallWindowSize;
		this.callIt = calls;
		this.evidenceIt = Iterators.peekingIterator(evidence);
		this.assignEvidenceToSingleBreakpoint = assignEvidenceToSingleBreakpoint;
		this.dump = dump;
	}
	private void buffer(VariantContextDirectedEvidence variant) {
		ActiveVariant av = new ActiveVariant(variant);
		variantBuffer.add(av);
		if (StringUtils.isNotBlank(av.id)) {
			bufferedVariantId.put(av.id, av);
		}
	}
	@Override
	protected VariantContextDirectedEvidence computeNext() {
		if (variantBuffer.isEmpty()) {
			if (!callIt.hasNext()) {
				if (Defaults.SANITY_CHECK_ITERATORS) {
					// we have no more calls to make so this doesn't actually need to be done
					// unless we're sanity checking
					while (evidenceIt.hasNext()) {
						assignEvidence(evidenceIt.next());
					}
				}
				return endOfData();
			}
			buffer(callIt.next());
		}
		ActiveVariant variant = variantBuffer.peek();
		bufferVariantsBefore(variant.startLocation + 2 * (maxCallRange + 1));
		processEvidenceBefore(variant.startLocation + maxCallRange + 1);
		variant = variantBuffer.poll();
		if (StringUtils.isNotBlank(variant.id)) {
			bufferedVariantId.remove(variant.id);
		}
		return variant.callVariant();
	}
	private void processEvidenceBefore(long position) {
		while (evidenceIt.hasNext() && context.getLinear().getStartLinearCoordinate(evidenceIt.peek().getBreakendSummary()) - context.getVariantCallingParameters().breakendMargin <= position) {
			assignEvidence(evidenceIt.next());
		}
	}
	/**
	 * Assigns the given evidence to the appropriate call
	 * 
	 */
	private void assignEvidence(DirectedEvidence evidence) {
		// TODO: replace overlap implementation with interval tree
		boolean evidenceCalled = false;
		BreakendSummary bs = evidence.getBreakendSummary();
		bs = context.getVariantCallingParameters().withMargin(bs);
		long endLocation = context.getLinear().getEndLinearCoordinate(bs);
		if (assignEvidenceToSingleBreakpoint) {
			ActiveVariant best = null;
			for (ActiveVariant v : variantBuffer) {
				if (v.startLocation > endLocation) break;
				if (v.location.overlaps(bs)) {
					if (best == null || ByScoreAscPositionDesc.compare(v, best) > 0) { 
						best = v;
					}
				}
			}
			if (best != null) {
				ActiveVariant mate = bufferedVariantId.get(best.mateid);
				if (mate != null && mate.location.overlaps(bs) && allocateToHighBreakend(evidence)) {
					// special case: evidence overlaps 
					mate.attributeEvidence(evidence);
				} else {
					best.attributeEvidence(evidence);
				}
				evidenceCalled = true;
			}
		} else {
			for (ActiveVariant v : variantBuffer) {
				if (v.startLocation > endLocation) break;
				if (v.location.overlaps(bs)) {
					v.attributeEvidence(evidence);
					evidenceCalled = true;
				}
			}
		}
		if (!evidenceCalled && dump != null) {
			// evidence does not provide support for any call
			// write out now before we drop it
			dump.writeEvidence(evidence, null);
		}
	}
	/**
	 * Determines which breakend to allocate evidence that overlaps both sides of the breakend
	 * @param e
	 * @return
	 */
	private boolean allocateToHighBreakend(DirectedEvidence evidence) {
		// want even allocation of evidence to both sides
		// want local and remote for same evidence allocated to different sides
		// want read pair evidence to be allocated to different sides
		String commonIdentifier;
		boolean flip = false;
		if (evidence instanceof NonReferenceReadPair) {
			// read name same for both sides of Discordant pairs
			SAMRecord local = ((NonReferenceReadPair) evidence).getLocalledMappedRead();
			commonIdentifier = local.getReadName();
			flip = local.getSecondOfPairFlag();
		} else if (evidence instanceof RemoteEvidence) {
			commonIdentifier = ((RemoteEvidence)evidence).asLocal().getEvidenceID();
			flip = true;
		} else if (evidence instanceof VariantContextDirectedEvidence) {
			commonIdentifier = ((VariantContextDirectedEvidence)evidence).getAttributeAsString(VcfSvConstants.BREAKEND_EVENT_ID_KEY, evidence.getEvidenceID());
		} else {
			commonIdentifier = evidence.getEvidenceID();
		}
		boolean allocateLow = (Integer.bitCount(commonIdentifier.hashCode()) & 1) == 1; // randomly allocate high/low based on string hash
		allocateLow ^= flip;
		return allocateLow;
	}
	private void bufferVariantsBefore(long position) {
		while (callIt.hasNext() && (variantBuffer.isEmpty() || variantBuffer.peekLast().startLocation <= position)) {
			buffer(callIt.next());
		}
	}
	private String trackedBufferName_variantBuffer = "annotate.variantBuffer";
	private String trackedBufferName_bufferedVariantId = "annotate.bufferedVariantId";
	@Override
	public void setTrackedBufferContext(String context) {
		this.trackedBufferName_variantBuffer = context + ".annotate.variantBuffer";
		this.trackedBufferName_bufferedVariantId = context + ".annotate.bufferedVariantId";
	}
	@Override
	public List<NamedTrackedBuffer> currentTrackedBufferSizes() {
		return ImmutableList.of(
				new NamedTrackedBuffer(trackedBufferName_variantBuffer, variantBuffer.size()),
				new NamedTrackedBuffer(trackedBufferName_bufferedVariantId, bufferedVariantId.size())
				);
	}
}
