package com.bridgefund.assistanceportal;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

/**
 * Simulated grant-eligibility review. Unlike ClearPath's prior-auth adjudicator, there is no
 * appeal workflow here — a request is decided exactly once and memoized: every subsequent call
 * for the same referral number replays that stored decision rather than re-deriving one, so
 * "the same referral always gets the same answer" holds even across the warm-up window (a fresh
 * hash-based re-roll on replay could otherwise contradict an earlier warm-up-forced approval).
 * Deterministic so a live demo behaves predictably: the first two distinct referrals after a
 * fresh start (or reset) are always approved — a guaranteed clean opening beat — after which
 * roughly one in four NEW requests is denied (eligibility-rule style reasons). State is
 * in-memory by design; a restart simply forgets past submissions.
 */
@Service
public class FinancialAssistanceAdjudicator {

    public record Decision(
            String referralNumber, String program, String medication, String decision,
            Integer securedAmount, String denialReason, String reviewer,
            long turnaroundMs, Instant decidedAt) {
    }

    private static final List<String> REVIEWERS = List.of(
            "D. Alvarez, Program Manager", "K. Osei, Case Coordinator",
            "L. Fitzgerald, Grants Administrator", "P. Nakamura, Case Coordinator");

    private static final List<String> DENIAL_REASONS = List.of(
            "Household income exceeds this fund's eligibility threshold",
            "Diagnosis is not covered under the fund's current award cycle",
            "Annual per-patient assistance cap already reached for this program",
            "Plan type is not eligible for third-party copay assistance under program rules");

    private final Map<String, Decision> decisionsByReferral = new ConcurrentHashMap<>();
    private final AtomicInteger distinctSeen = new AtomicInteger(0);
    private final Deque<Decision> recent = new ArrayDeque<>();
    private final boolean simulateLatency;

    public FinancialAssistanceAdjudicator() {
        this(true);
    }

    FinancialAssistanceAdjudicator(boolean simulateLatency) {
        this.simulateLatency = simulateLatency;
    }

    public Decision decide(String referralNumber, String medication, Integer copayAmount) {
        Decision cached = decisionsByReferral.get(referralNumber);
        if (cached != null) {
            return cached;
        }

        int h = Math.abs(referralNumber.hashCode());

        // Simulated review latency (deterministic per referral, ~0.6-1.1s).
        long turnaround = 600 + (h % 500);
        if (simulateLatency) {
            try {
                Thread.sleep(turnaround);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Warm-up: the first two distinct referrals seen (post start/reset) always approve.
        boolean warmup = distinctSeen.incrementAndGet() <= 2;
        boolean denied = !warmup && h % 4 == 0;

        Decision decision = new Decision(
                referralNumber, "BridgeFund Patient Assistance", medication,
                denied ? "DENIED" : "APPROVED",
                denied ? null : securedAmount(h, copayAmount),
                denied ? DENIAL_REASONS.get(h % DENIAL_REASONS.size()) : null,
                REVIEWERS.get(h % REVIEWERS.size()),
                turnaround, Instant.now());

        // Racing callers for a brand-new referral number settle on whichever decision wins the
        // map insert, so every caller (and the log) sees one consistent, cached answer.
        Decision winner = decisionsByReferral.merge(referralNumber, decision, (existing, ignored) -> existing);
        if (winner == decision) {
            remember(decision);
        }
        return winner;
    }

    public synchronized List<Decision> recentDecisions() {
        return new ArrayList<>(recent);
    }

    /** Forgets all submission history — paired with HealthRx's demo reset so reruns behave identically. */
    public synchronized void reset() {
        decisionsByReferral.clear();
        distinctSeen.set(0);
        recent.clear();
    }

    private synchronized void remember(Decision d) {
        recent.addFirst(d);
        while (recent.size() > 50) {
            recent.removeLast();
        }
    }

    /** A grant covering 50-80% of the copay (deterministic per referral), floored at $250, capped at $5000. */
    private static int securedAmount(int hash, Integer copayAmount) {
        int copay = copayAmount != null && copayAmount > 0 ? copayAmount : 800;
        double coverage = 0.5 + (hash % 31) / 100.0; // 0.50 - 0.80
        int amount = (int) Math.round(copay * coverage / 50.0) * 50;
        return Math.max(250, Math.min(5000, amount));
    }
}
