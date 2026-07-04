package com.clearpath.payerportal;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * Simulated utilization-management review. Deterministic so a live demo behaves predictably:
 * the first two distinct referrals after a fresh start (or reset) are always approved — the
 * presenter's opening beat is the clean "agent advanced the referral" story — after which
 * roughly one in five referrals is denied on first submission (missing documentation style
 * reasons); any resubmission of the same referral number is approved — the "appeal succeeded"
 * story. State is in-memory by design; a restart simply forgets past submissions.
 */
@Service
public class PriorAuthAdjudicator {

    public record Decision(
            String referralNumber, String payer, String medication, String decision,
            String authorizationNumber, String denialReason, String reviewer,
            int attempt, long turnaroundMs, Instant decidedAt) {
    }

    private static final List<String> REVIEWERS = List.of(
            "M. Havel, PharmD", "R. Okonkwo, RN", "S. Lindqvist, MD", "T. Aramaki, PharmD");

    private static final List<String> DENIAL_REASONS = List.of(
            "Step therapy documentation required: first-line agent trial not on file",
            "Recent lab work missing from submission (within 90 days required)",
            "Chart notes do not document disease severity criteria");

    private final Map<String, Integer> attemptsByReferral = new ConcurrentHashMap<>();
    private final Deque<Decision> recent = new ArrayDeque<>();
    private final boolean simulateLatency;

    public PriorAuthAdjudicator() {
        this(true);
    }

    PriorAuthAdjudicator(boolean simulateLatency) {
        this.simulateLatency = simulateLatency;
    }

    public Decision adjudicate(String referralNumber, String payer, String medication) {
        int attempt = attemptsByReferral.merge(referralNumber, 1, Integer::sum);
        int h = Math.abs(referralNumber.hashCode());

        // Simulated review latency (deterministic per referral, ~0.7-1.3s) so the agent's
        // "contacting the payer" moment is visible but never slow enough to stall a demo.
        long turnaround = 700 + (h % 600);
        if (simulateLatency) {
            try {
                Thread.sleep(turnaround);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Warm-up: the first two distinct referrals seen (post start/reset) always approve.
        boolean warmup = attemptsByReferral.size() <= 2;
        boolean denied = !warmup && attempt == 1 && h % 5 == 0;
        Decision decision = new Decision(
                referralNumber,
                payer == null || payer.isBlank() ? "ClearPath-administered plan" : payer,
                medication,
                denied ? "DENIED" : "APPROVED",
                denied ? null : authNumber(h, attempt),
                denied ? DENIAL_REASONS.get(h % DENIAL_REASONS.size()) : null,
                REVIEWERS.get(h % REVIEWERS.size()),
                attempt, turnaround, Instant.now());
        remember(decision);
        return decision;
    }

    public synchronized List<Decision> recentDecisions() {
        return new ArrayList<>(recent);
    }

    /** Forgets all submission history — paired with HealthRx's demo reset so reruns behave identically. */
    public synchronized void reset() {
        attemptsByReferral.clear();
        recent.clear();
    }

    private synchronized void remember(Decision d) {
        recent.addFirst(d);
        while (recent.size() > 50) {
            recent.removeLast();
        }
    }

    private static String authNumber(int hash, int attempt) {
        return "CP-" + Integer.toString(hash * 31 + attempt, 36).toUpperCase();
    }
}
