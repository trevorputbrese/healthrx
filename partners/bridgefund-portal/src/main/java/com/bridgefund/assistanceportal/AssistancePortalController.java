package com.bridgefund.assistanceportal;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.bridgefund.assistanceportal.FinancialAssistanceAdjudicator.Decision;

/**
 * The portal surface: one decision endpoint for partners (HealthRx's agent calls this), a JSON
 * log, and a tiny self-refreshing HTML page a presenter can keep open to show the "foundation
 * side" receiving requests live. No SPA, no build step — a demo prop, not a product.
 */
@RestController
public class AssistancePortalController {

    public record DecisionRequest(String referralNumber, String patientRef, String medication,
            Integer copayAmount, String requestedBy, String justification) {
    }

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("MMM d, HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private final FinancialAssistanceAdjudicator adjudicator;

    public AssistancePortalController(FinancialAssistanceAdjudicator adjudicator) {
        this.adjudicator = adjudicator;
    }

    @PostMapping("/api/financial-assistance/decision")
    public Map<String, Object> decide(@RequestBody DecisionRequest request) {
        if (request.referralNumber() == null || request.referralNumber().isBlank()) {
            throw new IllegalArgumentException("referralNumber is required");
        }
        Decision d = adjudicator.decide(request.referralNumber().trim(),
                request.medication(), request.copayAmount());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("program", d.program());
        out.put("referralNumber", d.referralNumber());
        out.put("decision", d.decision());
        if (d.securedAmount() != null) {
            out.put("securedAmount", d.securedAmount());
        }
        if (d.denialReason() != null) {
            out.put("denialReason", d.denialReason());
        }
        out.put("reviewer", d.reviewer());
        out.put("turnaroundMs", d.turnaroundMs());
        out.put("decidedAt", d.decidedAt().toString());
        return out;
    }

    @GetMapping("/api/financial-assistance/log")
    public List<Decision> log() {
        return adjudicator.recentDecisions();
    }

    /** Wipes submission history; HealthRx's demo reset calls this so reruns adjudicate identically. */
    @PostMapping("/api/admin/reset")
    public Map<String, Object> reset() {
        adjudicator.reset();
        return Map.of("reset", true);
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String home() {
        StringBuilder rows = new StringBuilder();
        for (Decision d : adjudicator.recentDecisions()) {
            boolean approved = "APPROVED".equals(d.decision());
            rows.append("<tr><td>").append(TS.format(d.decidedAt()))
                    .append("</td><td class=\"mono\">").append(escape(d.referralNumber()))
                    .append("</td><td>").append(escape(d.medication() == null ? "—" : d.medication()))
                    .append("</td><td><span class=\"pill ").append(approved ? "ok" : "no").append("\">")
                    .append(d.decision()).append("</span></td><td>")
                    .append(approved ? "$" + d.securedAmount() + " grant" : escape(d.denialReason()))
                    .append("</td><td>").append(escape(d.reviewer())).append("</td></tr>");
        }
        if (rows.isEmpty()) {
            rows.append("<tr><td colspan=\"6\" class=\"empty\">No assistance requests received yet.</td></tr>");
        }
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta http-equiv="refresh" content="5">
                <title>BridgeFund Patient Assistance — Case Portal</title>
                <style>
                  body { font-family: 'Segoe UI', system-ui, sans-serif; margin: 0; background: #eef6f2; color: #1f2d27; }
                  header { background: #1c6b5a; color: #fff; padding: 18px 32px; }
                  header h1 { margin: 0; font-size: 20px; letter-spacing: 0.5px; }
                  header p { margin: 4px 0 0; font-size: 13px; opacity: 0.9; }
                  main { padding: 24px 32px; }
                  table { width: 100%%; border-collapse: collapse; background: #fff; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
                  th, td { text-align: left; padding: 9px 12px; font-size: 13px; border-bottom: 1px solid #dcece5; }
                  th { background: #23856d; color: #fff; font-weight: 600; }
                  .mono { font-family: ui-monospace, monospace; }
                  .pill { padding: 2px 9px; border-radius: 999px; font-weight: 700; font-size: 12px; }
                  .pill.ok { background: #d7f0e2; color: #166a44; }
                  .pill.no { background: #fbe2d8; color: #a1451f; }
                  .empty { color: #7b8d85; font-style: italic; }
                  .note { font-size: 12px; color: #5c6e66; margin-top: 14px; }
                </style></head>
                <body>
                <header>
                  <h1>BridgeFund Patient Assistance &mdash; Case Portal</h1>
                  <p>Independent copay &amp; patient-assistance foundation &middot; External partner system (not part of HealthRx)</p>
                </header>
                <main>
                  <table>
                    <thead><tr><th>Received</th><th>Referral</th><th>Medication</th>
                    <th>Decision</th><th>Detail</th><th>Reviewer</th></tr></thead>
                    <tbody>%s</tbody>
                  </table>
                  <p class="note">This page refreshes every 5 seconds. Requests arrive via the portal API from
                  authorized pharmacy partners &mdash; during the HealthRx demo, from the Financial Assistance Agent.</p>
                </main>
                </body></html>""".formatted(rows.toString());
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
