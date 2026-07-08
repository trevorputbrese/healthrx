package com.healthrx.agent.access.core;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.healthrx.agent.access.config.AgentProperties;
import com.healthrx.agent.access.mcp.McpSql;

/**
 * The stuck-referral scan (phase-3-design.md §6 Access run-loop step 1): every scan interval
 * (real time, no LLM) read the sim clock and referral ages via audited Postgres MCP queries.
 *
 * <p><b>Baseline suppression:</b> on startup and on every pause->resume transition (which is how
 * a demo reset manifests to the agent — reset pauses it), the first scan records currently-stuck
 * episodes as the baseline and recommends nothing. Only referrals that <em>become</em> stuck
 * afterwards trigger, capped per scan. Without this, the seeded world (~50 pre-active referrals
 * older than the thresholds at the anchor) would flood the feed with LLM calls on resume.
 */
@Service
@ConditionalOnProperty(name = "healthrx.agent.scan-enabled", havingValue = "true", matchIfMissing = true)
public class StuckScanService {

    private static final Logger log = LoggerFactory.getLogger(StuckScanService.class);

    private final McpSql sql;
    private final Guards guards;
    private final AccessAgentService agent;
    private final AgentProperties props;

    private final Set<String> baseline = new HashSet<>();
    private boolean baselineTaken;
    private boolean wasPaused = true;

    public StuckScanService(McpSql sql, Guards guards, AccessAgentService agent, AgentProperties props) {
        this.sql = sql;
        this.guards = guards;
        this.agent = agent;
        this.props = props;
    }

    public record StuckReferral(UUID referralId, UUID patientId, String rule, String statusEnteredAt) {
        String episodeKey() {
            return referralId + "/" + rule + "/" + statusEnteredAt;
        }
    }

    @Scheduled(fixedDelayString = "${healthrx.agent.scan-interval-ms:60000}", initialDelay = 15000)
    public void scan() {
        try {
            boolean paused = guards.paused();
            if (paused) {
                wasPaused = true;
                return;
            }
            List<StuckReferral> stuck = findStuck(guards.simClock().now());
            if (wasPaused || !baselineTaken) {
                baseline.clear();
                stuck.forEach(s -> baseline.add(s.episodeKey()));
                baselineTaken = true;
                wasPaused = false;
                log.info("Stuck-scan baseline recorded: {} episode(s) suppressed", baseline.size());
                return;
            }

            int budget = Math.max(1, props.scanCap());
            for (StuckReferral s : stuck) {
                if (budget == 0) {
                    log.info("Per-scan cap reached; remaining newly-stuck referrals wait for the next scan");
                    return;
                }
                if (baseline.contains(s.episodeKey())) {
                    continue;
                }
                long decided = guards.decidedCountForReferral(s.referralId());
                UUID episodeId = AgentIds.scanEpisodeId(props.name(), s.referralId(), s.rule(),
                        s.statusEnteredAt(), decided);
                UUID recommendationId = AgentIds.recommendationId(props.name(), episodeId);
                if (guards.recommendationExists(recommendationId)) {
                    baseline.add(s.episodeKey()); // already handled this episode; suppress future scans
                    continue;
                }
                log.info("Newly-stuck referral {} ({}) — triaging", s.referralId(), s.rule());
                agent.onStuckEpisode(episodeId, s.referralId(), s.patientId(), s.rule());
                baseline.add(s.episodeKey());
                budget--;
            }
        } catch (Exception e) {
            log.error("Stuck scan failed (will retry next interval)", e);
        }
    }

    /** Stuck = PA pending past its threshold, or any pre-active referral untouched past its. */
    private List<StuckReferral> findStuck(Instant simNow) {
        String sqlText = """
                select id, patient_id, rule, entered_at from (
                  select r.id, r.patient_id, 'PA_PENDING' as rule,
                         r.pa_submitted_at as entered_at
                  from referrals r
                  where r.current_status = 'PRIOR_AUTH_SUBMITTED'
                    and r.pa_submitted_at < timestamptz '%1$s' - interval '%2$d days'
                  union all
                  select r.id, r.patient_id, 'STATUS_STALLED' as rule,
                         r.updated_at as entered_at
                  from referrals r
                  where r.current_status in ('ELIGIBILITY_IDENTIFIED', 'BENEFITS_INVESTIGATION',
                        'PRIOR_AUTH_DENIED', 'FINANCIAL_ASSISTANCE_REVIEW')
                    and r.updated_at < timestamptz '%1$s' - interval '%3$d days'
                ) stuck
                order by entered_at asc
                limit 50""".formatted(simNow, props.paStuckDays(), props.statusStuckDays());
        return sql.query(sqlText).stream()
                .map(row -> new StuckReferral(
                        UUID.fromString(String.valueOf(row.get("id"))),
                        UUID.fromString(String.valueOf(row.get("patient_id"))),
                        String.valueOf(row.get("rule")),
                        String.valueOf(row.get("entered_at"))))
                .toList();
    }
}
