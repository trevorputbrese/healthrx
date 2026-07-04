#!/usr/bin/env python3
"""Deterministic seed-data generator for the HealthRx specialty-pharmacy demo.

Running ``python3 scripts/generate_seed.py`` writes a Flyway migration at
``backend/src/main/resources/db/migration/V2__seed_data.sql`` containing
deterministic INSERT statements that satisfy every constraint and FK in
``V1__schema.sql`` and exercise the metrics described in
``metric-definitions.md``.

Design rules (see the build-contract docs for the why):

* Determinism. Python's RNG is seeded with a fixed constant and every entity
  UUID is derived with ``uuid.uuid5`` from a stable key, so rebuilds are
  byte-identical. No ``uuid4`` and no wall-clock values ever reach the SQL.
* Application clock. ``DEMO_NOW`` is the pinned demo instant (2026-06-29). All
  dates/timestamps are authored as fixed offsets from it via the ``days_ago`` /
  ``ts_days_ago`` helpers. Seed data spans >=210 days back so the 180-day trend
  buckets and 90-day adherence windows are fully populated.
* Milestone consistency. A referral's milestone timestamps and its
  ``referral_status_history`` chain are generated together from a single
  canonical status-progression model, so they are always monotonic and
  consistent with ``current_status`` (and with the linked therapy for ACTIVE).

The script is committed dev tooling: it is meant to be read.
"""

from __future__ import annotations

import random
import uuid
from collections import Counter
from datetime import date, timedelta
from pathlib import Path

# --------------------------------------------------------------------------- #
# Application clock anchor
# --------------------------------------------------------------------------- #

DEMO_NOW = date(2026, 6, 29)
RNG_SEED = 20260629
NAMESPACE = uuid.NAMESPACE_DNS


def stable_uuid(key: str) -> str:
    """Deterministic UUID for a stable entity key, e.g. ``healthrx:patient:7``."""
    return str(uuid.uuid5(NAMESPACE, key))


def days_ago(n: int) -> date:
    """Return the date ``n`` days before DEMO_NOW (negative ``n`` => future)."""
    return DEMO_NOW - timedelta(days=n)


def date_str(d: date) -> str:
    return d.isoformat()


def ts_days_ago(n: int, hour: int = 12, minute: int = 0) -> str:
    """ISO-8601 UTC timestamp ``n`` days before DEMO_NOW at the given time."""
    d = DEMO_NOW - timedelta(days=n)
    return f"{d.isoformat()}T{hour:02d}:{minute:02d}:00Z"


# --------------------------------------------------------------------------- #
# SQL literal helpers
# --------------------------------------------------------------------------- #


def sql_str(value: str) -> str:
    """Quote a text value, doubling embedded apostrophes."""
    return "'" + value.replace("'", "''") + "'"


def sql_val(value) -> str:
    """Render a Python value as a SQL literal."""
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, date):
        return sql_str(value.isoformat())
    return sql_str(str(value))


def insert(table: str, columns: list[str], row: dict) -> str:
    cols = ", ".join(columns)
    vals = ", ".join(sql_val(row.get(c)) for c in columns)
    return f"INSERT INTO {table} ({cols}) VALUES ({vals});"


# --------------------------------------------------------------------------- #
# Reference vocabulary (must match V1 check constraints verbatim)
# --------------------------------------------------------------------------- #

DISEASE_STATES = ["Oncology", "Rheumatology", "Multiple sclerosis", "Gastroenterology"]

# Status order along the "happy path" (CANCELLED handled separately).
STATUS_ORDER = [
    "ELIGIBILITY_IDENTIFIED",
    "BENEFITS_INVESTIGATION",
    "PRIOR_AUTH_SUBMITTED",
    "PRIOR_AUTH_APPROVED",
    "FINANCIAL_ASSISTANCE_REVIEW",
    "READY_TO_FILL",
    "DELIVERY_SCHEDULED",
    "ACTIVE_THERAPY",
]

# Map each status (when reached) to the milestone timestamp it writes and the
# phase2 event recorded in referral_status_history.
PHASE2_EVENT = {
    "ELIGIBILITY_IDENTIFIED": "ReferralCreated",
    "BENEFITS_INVESTIGATION": "BenefitsInvestigationStarted",
    "PRIOR_AUTH_SUBMITTED": "PriorAuthorizationSubmitted",
    "PRIOR_AUTH_APPROVED": "PriorAuthorizationApproved",
    "PRIOR_AUTH_DENIED": "PriorAuthorizationDenied",
    "FINANCIAL_ASSISTANCE_REVIEW": "FinancialAssistanceFound",
    "READY_TO_FILL": "ReadyToFill",
    "DELIVERY_SCHEDULED": "DeliveryScheduled",
    "ACTIVE_THERAPY": "TherapyActivated",
    "CANCELLED": "ReferralCancelled",
}

ROUTES_BY_DISEASE = {
    "Oncology": ["Oral", "Infusion", "Injectable"],
    "Rheumatology": ["Subcutaneous", "Injectable", "Oral"],
    "Multiple sclerosis": ["Oral", "Infusion", "Injectable"],
    "Gastroenterology": ["Subcutaneous", "Infusion", "Oral"],
}

DIAGNOSES = {
    "Oncology": ["Multiple myeloma", "Chronic lymphocytic leukemia", "Metastatic breast cancer"],
    "Rheumatology": ["Rheumatoid arthritis", "Psoriatic arthritis", "Ankylosing spondylitis"],
    "Multiple sclerosis": ["Relapsing-remitting MS", "Secondary progressive MS", "Clinically isolated syndrome"],
    "Gastroenterology": ["Crohn's disease", "Ulcerative colitis", "Eosinophilic esophagitis"],
}

# Fictional brand names, 3 per disease state (12 total).
MEDICATION_NAMES = {
    "Oncology": [
        ("Oncora", "Oral", True),
        ("Velmacin", "Infusion", True),
        ("Tarvexa", "Injectable", False),
    ],
    "Rheumatology": [
        ("Immunza", "Subcutaneous", True),
        ("Rheumavy", "Injectable", False),
        ("Jakvoren", "Oral", False),
    ],
    "Multiple sclerosis": [
        ("Neurosphere", "Oral", True),
        ("Mylenta-S", "Infusion", True),
        ("Releva", "Injectable", False),
    ],
    "Gastroenterology": [
        ("Gastronib", "Subcutaneous", True),
        ("Colirex", "Infusion", False),
        ("Entovia", "Oral", False),
    ],
}

FIRST_NAMES = [
    "Jordan", "Avery", "Riley", "Casey", "Morgan", "Quinn", "Harper", "Rowan",
    "Sasha", "Devon", "Elliot", "Marlowe", "Priya", "Mateo", "Noor", "Hana",
    "Ezra", "Lena", "Felix", "Tessa", "Omar", "Ines", "Cyrus", "Dahlia",
    "Soren", "Talia", "Bishop", "Wren", "Cleo", "Idris", "Mira", "Pascal",
    "Linnea", "Rafael", "Yuki", "Anaya", "Gideon", "Saoirse", "Niko", "Bex",
]

LAST_NAMES = [
    "Ellis", "Navarro", "Okafor", "Lindqvist", "Romano", "Cho", "Delacroix",
    "Ferreira", "Haddad", "Whitlock", "Bauer", "Sandoval", "Kovac", "Mensah",
    "Petrov", "Aziz", "Lindgren", "Castellano", "Ng", "Oyelaran", "Schreiber",
    "Vasquez", "Forsythe", "Dimitriou", "Abara", "Yamamoto", "Brennan",
    "Salgado", "Theriault", "Nwosu", "Calderon", "Rhodes", "Eskildsen",
    "Marchetti", "Bjornson", "Quintero", "Hollis", "Adeyemi", "Larsson", "Vega",
]

CLINIC_DEFS = [
    ("Northside Oncology", "Northeast", "Oncology"),
    ("Lakeshore Rheumatology", "Midwest", "Rheumatology"),
    ("Summit Neurology", "West", "Multiple sclerosis"),
    ("Riverbend Digestive Health", "South", "Gastroenterology"),
]

# 8 active care team members with realistic roles.
CARE_TEAM_DEFS = [
    ("Maya Patel", "Clinical Pharmacist"),
    ("Daniel Okeke", "Pharmacy Liaison"),
    ("Sofia Reyes", "Patient Care Coordinator"),
    ("Liam Brennan", "Pharmacy Technician"),
    ("Aisha Khan", "Clinical Pharmacist"),
    ("Noah Whitfield", "Pharmacy Liaison"),
    ("Grace Lin", "Patient Care Coordinator"),
    ("Owen Marsh", "Pharmacy Technician"),
]

# 12 payers with a mix of payer types.
PAYER_DEFS = [
    ("Atlas Commercial", "Commercial"),
    ("Beacon Health Plan", "Commercial"),
    ("Cascade PPO", "Commercial"),
    ("Meridian Choice", "Commercial"),
    ("Pinnacle Select", "Commercial"),
    ("Heartland Mutual", "Commercial"),
    ("Medicare Part B Regional", "Medicare"),
    ("Silverline Medicare Advantage", "Medicare"),
    ("Evergreen Medicare", "Medicare"),
    ("State Care Medicaid", "Medicaid"),
    ("Unity Medicaid Plan", "Medicaid"),
    ("Bridgeway Medicaid", "Medicaid"),
]

PRIORITIES = ["LOW", "MEDIUM", "HIGH", "URGENT"]


# --------------------------------------------------------------------------- #
# Builder
# --------------------------------------------------------------------------- #


class SeedBuilder:
    """Accumulates rows for every table in dependency order."""

    def __init__(self) -> None:
        self.rng = random.Random(RNG_SEED)
        self.clinics: list[dict] = []
        self.payers: list[dict] = []
        self.care_team: list[dict] = []
        self.medications: list[dict] = []
        self.patients: list[dict] = []
        self.therapies: list[dict] = []
        self.referrals: list[dict] = []
        self.status_history: list[dict] = []
        self.referral_notes: list[dict] = []
        self.tasks: list[dict] = []
        self.outreach_events: list[dict] = []
        self.clinical_interventions: list[dict] = []
        self.fills: list[dict] = []

        # Lookups keyed by disease state for convenient assignment.
        self.clinic_by_disease: dict[str, dict] = {}
        self.meds_by_disease: dict[str, list[dict]] = {ds: [] for ds in DISEASE_STATES}

        # Monotonic counters for stable per-table UUID keys.
        self._status_seq = 0
        self._note_seq = 0
        self._task_seq = 0
        self._outreach_seq = 0
        self._intervention_seq = 0
        self._fill_seq = 0
        self._old_active_seq = 0  # cycles adherence buckets among old therapies

        # Deterministic activation schedule for the 40 bulk ACTIVE_THERAPY
        # referrals (indexed by active_seq): the number of days before DEMO_NOW
        # each therapy was activated. ~14 land within the last 30 days (so the
        # default dashboard window is populated) and the remainder spread across
        # ~180 days (so all six monthly trend buckets stay populated).
        self._active_schedule = self._build_active_schedule()

    @staticmethod
    def _build_active_schedule() -> list[int]:
        """Activation-age (days before DEMO_NOW) per bulk active_seq, 0..39."""
        # Recent cohort: 14 activations spread across 2..28 days ago.
        recent = [2, 4, 6, 8, 10, 13, 15, 17, 19, 21, 23, 25, 27, 28]
        # Older cohort: 26 activations spread across ~40..195 days ago, hitting
        # every month from December back through June for the trend buckets.
        older = [40, 48, 55, 62, 70, 78, 85, 92, 100, 108, 115, 122, 130,
                 138, 145, 152, 158, 164, 170, 175, 180, 184, 188, 191, 193, 195]
        schedule = recent + older
        # Interleave so recent/older activations alternate in active_seq order;
        # this keeps medication/owner round-robin assignment well mixed.
        interleaved: list[int] = []
        ri, oi = 0, 0
        for k in range(len(schedule)):
            if k % 3 == 0 and ri < len(recent):
                interleaved.append(recent[ri]); ri += 1
            elif oi < len(older):
                interleaved.append(older[oi]); oi += 1
            elif ri < len(recent):
                interleaved.append(recent[ri]); ri += 1
        return interleaved

    # -- reference tables --------------------------------------------------- #

    def build_clinics(self) -> None:
        for i, (name, region, disease) in enumerate(CLINIC_DEFS):
            row = {
                "id": stable_uuid(f"healthrx:clinic:{i}"),
                "name": name,
                "region": region,
                "created_at": ts_days_ago(300, hour=8),
            }
            self.clinics.append(row)
            self.clinic_by_disease[disease] = row

    def build_payers(self) -> None:
        for i, (name, payer_type) in enumerate(PAYER_DEFS):
            self.payers.append({
                "id": stable_uuid(f"healthrx:payer:{i}"),
                "name": name,
                "payer_type": payer_type,
                "created_at": ts_days_ago(300, hour=8),
            })

    def build_care_team(self) -> None:
        for i, (display_name, role) in enumerate(CARE_TEAM_DEFS):
            handle = display_name.lower().replace(" ", ".")
            self.care_team.append({
                "id": stable_uuid(f"healthrx:ctm:{i}"),
                "display_name": display_name,
                "role": role,
                "email": f"{handle}@healthrx.example",
                "active": True,
                "created_at": ts_days_ago(300, hour=8),
            })

    def build_medications(self) -> None:
        idx = 0
        for disease in DISEASE_STATES:
            for name, route, limited in MEDICATION_NAMES[disease]:
                row = {
                    "id": stable_uuid(f"healthrx:medication:{idx}"),
                    "name": name,
                    "disease_state": disease,
                    "route": route,
                    "limited_distribution": limited,
                    "active": True,
                    "created_at": ts_days_ago(300, hour=8),
                }
                self.medications.append(row)
                self.meds_by_disease[disease].append(row)
                idx += 1

    # -- helpers ------------------------------------------------------------ #

    def owner_for(self, i: int) -> dict:
        return self.care_team[i % len(self.care_team)]

    def payer_for(self, i: int) -> dict:
        return self.payers[i % len(self.payers)]

    # -- patients ----------------------------------------------------------- #

    def build_patients(self) -> None:
        """80 patients: first 4 are the named demo-scenario patients."""
        scenario_patients = [
            # (key, first, last, disease, mrn, dob)
            ("scenario_a", "Jordan", "Ellis", "Oncology", "PX-2042", date(1968, 4, 12)),
            ("scenario_b", "Priya", "Navarro", "Rheumatology", "PX-2043", date(1979, 9, 3)),
            ("scenario_c", "Marlowe", "Okafor", "Multiple sclerosis", "PX-2044", date(1985, 1, 27)),
            ("scenario_d", "Felix", "Lindqvist", "Gastroenterology", "PX-2045", date(1991, 11, 8)),
        ]

        for idx, (key, first, last, disease, mrn, dob) in enumerate(scenario_patients):
            clinic = self.clinic_by_disease[disease]
            self.patients.append({
                "id": stable_uuid(f"healthrx:patient:{idx}"),
                "demo_mrn": mrn,
                "first_name": first,
                "last_name": last,
                "date_of_birth": dob,
                "disease_state": disease,
                "clinic_id": clinic["id"],
                "primary_owner_id": self.owner_for(idx)["id"],
                "payer_id": self.payer_for(idx)["id"],
                "created_at": ts_days_ago(220 - idx, hour=9),
                "_key": key,
                "_index": idx,
                "_disease": disease,
            })

        # Remaining patients (indices 4..79), evenly across disease states.
        for idx in range(len(scenario_patients), 80):
            disease = DISEASE_STATES[idx % len(DISEASE_STATES)]
            clinic = self.clinic_by_disease[disease]
            first = FIRST_NAMES[idx % len(FIRST_NAMES)]
            # Shift the surname pairing per 40-index wave: both name arrays are length 40, so
            # without this the (first, last) combination repeats exactly every 40 patients and
            # the queue fills with apparent duplicate people (V10 repairs the shipped V2 data).
            last = LAST_NAMES[(idx * 7 + 3 + (idx // len(FIRST_NAMES)) * 11) % len(LAST_NAMES)]
            dob_year = 1950 + (idx * 11) % 50
            dob_month = 1 + (idx * 5) % 12
            dob_day = 1 + (idx * 13) % 28
            self.patients.append({
                "id": stable_uuid(f"healthrx:patient:{idx}"),
                "demo_mrn": f"PX-{2046 + (idx - 4)}",
                "first_name": first,
                "last_name": last,
                "date_of_birth": date(dob_year, dob_month, dob_day),
                "disease_state": disease,
                "clinic_id": clinic["id"],
                "primary_owner_id": self.owner_for(idx)["id"],
                "payer_id": self.payer_for(idx)["id"],
                "created_at": ts_days_ago(max(10, 215 - idx * 2), hour=9),
                "_key": f"patient_{idx}",
                "_index": idx,
                "_disease": disease,
            })

    # -- therapy creation --------------------------------------------------- #

    def make_therapy(
        self,
        key: str,
        patient: dict,
        status: str,
        start_date: date | None,
        current_refill_due_date: date | None = None,
        created_days_ago: int = 200,
    ) -> dict:
        disease = patient["_disease"]
        med = self.meds_by_disease[disease][self.rng.randrange(len(self.meds_by_disease[disease]))]
        therapy = {
            "id": stable_uuid(f"healthrx:therapy:{key}"),
            "patient_id": patient["id"],
            "medication_id": med["id"],
            "diagnosis": self.rng.choice(DIAGNOSES[disease]),
            "disease_state": disease,
            "status": status,
            "start_date": start_date,
            "end_date": None,
            "current_refill_due_date": current_refill_due_date,
            "created_at": ts_days_ago(created_days_ago, hour=10),
            "_medication_id": med["id"],
        }
        self.therapies.append(therapy)
        return therapy

    # -- status-history chain ---------------------------------------------- #

    def emit_history(self, referral: dict, milestones: dict) -> None:
        """Emit the ordered ReferralCreated..current chain for a referral.

        ``milestones`` maps status -> the date the referral entered it (the
        same instants used to set the referral's milestone columns). The chain
        is monotonic in changed_at and bounded by [received_at, DEMO_NOW].
        """
        owner_id = referral["owner_id"]
        for from_status, to_status, when_ts in milestones["chain"]:
            self._status_seq += 1
            self.status_history.append({
                "id": stable_uuid(f"healthrx:history:{self._status_seq}"),
                "referral_id": referral["id"],
                "from_status": from_status,
                "to_status": to_status,
                "changed_at": when_ts,
                "changed_by_id": owner_id,
                "note": None,
                "phase2_event_type": PHASE2_EVENT[to_status],
            })

    # -- referrals ---------------------------------------------------------- #

    def build_referrals(self) -> None:
        """Build 100 referrals across all statuses, including the 4 scenarios.

        Each referral entry carries a status-progression timeline so milestone
        columns and the history chain stay consistent. ACTIVE_THERAPY referrals
        also create+activate a therapy that fills hang off of later.
        """
        ref_index = 0  # global referral counter -> referral_number RX-100xx

        # ----- Scenario referrals (fixed, recognizable) ------------------- #
        self._build_scenario_a()
        self._build_scenario_b()
        self._build_scenario_c()
        self._build_scenario_d()
        ref_index = len(self.referrals)

        # ----- Bulk referrals across statuses ----------------------------- #
        # Status plan: meaningful count in every non-cancelled status plus
        # ~8 CANCELLED. We need >=40 ACTIVE therapies overall; scenario C is
        # already one active therapy, so allocate plenty of ACTIVE_THERAPY.
        status_plan = [
            ("ELIGIBILITY_IDENTIFIED", 8),
            ("BENEFITS_INVESTIGATION", 8),
            ("PRIOR_AUTH_SUBMITTED", 8),
            ("PRIOR_AUTH_APPROVED", 7),
            ("PRIOR_AUTH_DENIED", 6),
            ("FINANCIAL_ASSISTANCE_REVIEW", 7),
            ("READY_TO_FILL", 7),
            ("DELIVERY_SCHEDULED", 7),
            ("ACTIVE_THERAPY", 42),
            ("CANCELLED", 8),
        ]
        # Scenarios already contributed: A=FINANCIAL_ASSISTANCE_REVIEW,
        # B=PRIOR_AUTH_SUBMITTED, C=ACTIVE_THERAPY, D=ACTIVE_THERAPY.
        # That is 4 referrals; bulk plan above sums to 100 - but the 4
        # scenarios are extra recognizable rows, giving ~100 total. We trim the
        # bulk ACTIVE count by the 2 scenario actives and FA/PA by 1 each so the
        # grand total lands at exactly 100.
        status_plan = self._adjust_plan_for_scenarios(status_plan)

        # Patients available for bulk referrals (skip the 4 scenario patients,
        # which already own their scenario referrals; reuse the rest, and allow
        # some patients to carry more than one referral over time).
        bulk_patient_pool = self.patients[4:]

        # Build a flat list of statuses to assign.
        statuses: list[str] = []
        for status, count in status_plan:
            statuses.extend([status] * count)

        active_therapy_counter = 0
        for offset, status in enumerate(statuses):
            patient = bulk_patient_pool[offset % len(bulk_patient_pool)]
            self._build_bulk_referral(ref_index, patient, status,
                                      active_seq=active_therapy_counter)
            if status == "ACTIVE_THERAPY":
                active_therapy_counter += 1
            ref_index += 1

        # created_at is NOT NULL: anchor it to received_at for every referral.
        for ref in self.referrals:
            if ref["created_at"] is None:
                ref["created_at"] = ref["received_at"]

    def _adjust_plan_for_scenarios(self, plan: list[tuple[str, int]]) -> list[tuple[str, int]]:
        """Trim bulk counts so total referrals == 100 including 4 scenarios."""
        adjust = {
            "ACTIVE_THERAPY": -2,            # scenarios C, D
            "FINANCIAL_ASSISTANCE_REVIEW": -1,  # scenario A
            "PRIOR_AUTH_SUBMITTED": -1,      # scenario B
        }
        return [(s, c + adjust.get(s, 0)) for s, c in plan]

    # -- progression timeline ---------------------------------------------- #

    # Cumulative day gaps from received_at to each happy-path milestone.
    _PATH_SPAN = {
        "ELIGIBILITY_IDENTIFIED": 0,
        "BENEFITS_INVESTIGATION": 2,
        "PRIOR_AUTH_SUBMITTED": 5,
        "PRIOR_AUTH_APPROVED": 9,
        "PRIOR_AUTH_DENIED": 9,
        "FINANCIAL_ASSISTANCE_REVIEW": 11,
        "READY_TO_FILL": 13,
        "DELIVERY_SCHEDULED": 15,
        "ACTIVE_THERAPY": 18,
    }

    # Days from received_at to active_therapy_at along the full happy path
    # (sum of the chain gaps in _progression_dates). received_at is stamped
    # this many days before activation, a realistic, monotonic time-to-therapy.
    ACTIVE_PATH_SPAN = _PATH_SPAN["ACTIVE_THERAPY"]

    # Statuses a cancelled referral may be cancelled from (cycled by index).
    CANCEL_FROM_OPTIONS = [
        "ELIGIBILITY_IDENTIFIED", "BENEFITS_INVESTIGATION",
        "PRIOR_AUTH_SUBMITTED", "PRIOR_AUTH_DENIED",
    ]

    # Default per-step gaps (days) between consecutive milestones. The demo
    # scenarios rely on these exact values, so the default must never change.
    _DEFAULT_GAPS = {
        "BENEFITS_INVESTIGATION": 2,
        "PRIOR_AUTH_SUBMITTED": 3,
        "PRIOR_AUTH_APPROVED": 4,
        "PRIOR_AUTH_DENIED": 4,
        "FINANCIAL_ASSISTANCE_REVIEW": 2,
        "READY_TO_FILL": 2,
        "DELIVERY_SCHEDULED": 2,
        "ACTIVE_THERAPY": 3,
    }

    def _varied_gaps(self, ref_index: int) -> dict:
        """Deterministic per-referral gaps that spread PA turnaround (~1-10d)
        and overall time-to-therapy (~5-20d) without breaking monotonicity."""
        g = dict(self._DEFAULT_GAPS)
        # PA decision turnaround: 1..10 days.
        pa_turn = 1 + (ref_index * 3) % 10
        g["PRIOR_AUTH_APPROVED"] = pa_turn
        g["PRIOR_AUTH_DENIED"] = 1 + (ref_index * 7) % 10
        # Light variation on a couple of other steps for credible spread.
        g["BENEFITS_INVESTIGATION"] = 1 + (ref_index * 2) % 4   # 1..4
        g["PRIOR_AUTH_SUBMITTED"] = 2 + (ref_index * 5) % 4     # 2..5
        g["READY_TO_FILL"] = 1 + (ref_index) % 3                # 1..3
        return g

    def _min_received_age(self, status: str) -> int:
        """Smallest received-age (days) that keeps every milestone in the past."""
        return self._PATH_SPAN.get(status, 0) + 1

    def _span_to(self, status: str, gaps: dict) -> int:
        """Cumulative days from received_at to ``status`` for the given gaps."""
        if status == "PRIOR_AUTH_DENIED":
            order = ["BENEFITS_INVESTIGATION", "PRIOR_AUTH_SUBMITTED", "PRIOR_AUTH_DENIED"]
        else:
            order = []
            for s in STATUS_ORDER[1:]:  # skip ELIGIBILITY_IDENTIFIED (span 0)
                order.append(s)
                if s == status:
                    break
        return sum(gaps[s] for s in order)

    def _active_span_for(self, ref_index: int) -> int:
        """Total received->active span (days) for a given varied-gap referral."""
        return self._span_to("ACTIVE_THERAPY", self._varied_gaps(ref_index))

    def _progression_dates(self, status: str, received_days_ago: int,
                           gaps: dict | None = None) -> dict:
        """Compute milestone dates/timestamps for a happy-path ``status``.

        Returns a dict with ``received_at`` plus any set milestone timestamps,
        a ``chain`` of (from, to, ts) history tuples, and ``active_date`` when
        the referral reached ACTIVE_THERAPY.
        """
        # Step gaps (in days) between consecutive milestones along the path.
        # received -> benefits(+2) -> pa_sub(+3) -> pa_dec(+4) -> fa(+2)
        #   -> ready(+2) -> delivery(+2) -> active(+3)
        # Callers may pass per-referral ``gaps`` to spread time-to-therapy and
        # PA-turnaround values realistically; the default keeps the fixed gaps
        # used by the demo scenarios so their dates never shift.
        gaps = gaps or self._DEFAULT_GAPS

        # Determine which milestones are reached for a terminal status.
        if status == "PRIOR_AUTH_DENIED":
            reached = ["ELIGIBILITY_IDENTIFIED", "BENEFITS_INVESTIGATION",
                       "PRIOR_AUTH_SUBMITTED", "PRIOR_AUTH_DENIED"]
        else:
            reached = []
            for s in STATUS_ORDER:
                reached.append(s)
                if s == status:
                    break

        # Walk forward from received_at accumulating day offsets.
        day_cursor = received_days_ago
        result: dict = {"received_at": ts_days_ago(received_days_ago, hour=13)}
        milestone_ts: dict[str, str] = {}
        milestone_date: dict[str, date] = {}
        chain: list[tuple[str | None, str, str]] = []

        # Initial ReferralCreated row (from NULL to ELIGIBILITY_IDENTIFIED).
        chain.append((None, "ELIGIBILITY_IDENTIFIED",
                      ts_days_ago(received_days_ago, hour=13)))
        milestone_date["ELIGIBILITY_IDENTIFIED"] = days_ago(received_days_ago)

        prev = "ELIGIBILITY_IDENTIFIED"
        for s in reached[1:]:
            gap = gaps[s]
            # Floor at 1 (not 0): the latest milestone must land at least one
            # day before DEMO_NOW so no stamped timestamp is in the future.
            day_cursor = max(1, day_cursor - gap)
            ts = ts_days_ago(day_cursor, hour=14)
            chain.append((prev, s, ts))
            milestone_ts[s] = ts
            milestone_date[s] = days_ago(day_cursor)
            prev = s

        result["chain"] = chain
        result["milestone_ts"] = milestone_ts
        result["milestone_date"] = milestone_date
        result["final_status"] = status
        return result

    def _apply_milestones(self, referral: dict, prog: dict) -> None:
        """Copy reached milestone timestamps onto the referral columns."""
        m = prog["milestone_ts"]
        referral["benefits_investigation_started_at"] = m.get("BENEFITS_INVESTIGATION")
        referral["pa_submitted_at"] = m.get("PRIOR_AUTH_SUBMITTED")
        # pa_decided_at set on approve or deny.
        referral["pa_decided_at"] = m.get("PRIOR_AUTH_APPROVED") or m.get("PRIOR_AUTH_DENIED")
        referral["ready_to_fill_at"] = m.get("READY_TO_FILL")
        referral["delivery_scheduled_at"] = m.get("DELIVERY_SCHEDULED")
        referral["active_therapy_at"] = m.get("ACTIVE_THERAPY")

    # -- bulk referral ------------------------------------------------------ #

    def _build_bulk_referral(self, ref_index: int, patient: dict, status: str,
                             active_seq: int) -> None:
        disease = patient["_disease"]
        owner = self.owner_for(ref_index)
        payer = self.payers[patient["_index"] % len(self.payers)]
        med = self.meds_by_disease[disease][ref_index % len(self.meds_by_disease[disease])]

        # Received date: spread across ~7 months so trends are populated.
        # Earlier statuses get more recent receipts; ACTIVE older ones.
        # Per-referral gap variation spreads time-to-therapy (~5-20d) and PA
        # turnaround (~1-10d) across the population for credible medians.
        varied = self._varied_gaps(ref_index)

        active_days_ago = None  # set only on the ACTIVE_THERAPY path
        if status == "ACTIVE_THERAPY":
            # Drive the active population from a precomputed activation schedule
            # (see _active_schedule). A cohort of ~14 activates within the last
            # 30 days so the default dashboard tiles (median time-to-therapy,
            # financial assistance secured) are populated, while the rest stay
            # spread across ~180 days so the monthly trend buckets are too.
            active_days_ago = self._active_schedule[active_seq]
            # received_at precedes active_therapy_at by the (varied) chain span,
            # so active_therapy_at lands exactly on the scheduled day.
            received_days_ago = active_days_ago + self._active_span_for(ref_index)
        elif status == "CANCELLED":
            received_days_ago = 30 + (ref_index * 7) % 160
        else:
            received_days_ago = 8 + (ref_index * 6) % 70  # in-flight: recent

        # Ensure received_at is old enough to fit the full (varied) milestone
        # path with strictly increasing dates and no milestone on/after DEMO_NOW.
        span_status = (
            self.CANCEL_FROM_OPTIONS[ref_index % 4] if status == "CANCELLED" else status
        )
        min_age = self._span_to(span_status, varied) + 1 if span_status != "ELIGIBILITY_IDENTIFIED" else 1
        received_days_ago = max(received_days_ago, min_age)

        referral = self._new_referral_shell(ref_index, patient, owner, payer, med, disease)
        referral["current_status"] = status
        referral["priority"] = PRIORITIES[(ref_index + patient["_index"]) % len(PRIORITIES)]

        if status == "CANCELLED":
            self._populate_cancelled(referral, received_days_ago, ref_index)
        else:
            prog = self._progression_dates(status, received_days_ago, gaps=varied)
            referral["received_at"] = prog["received_at"]
            self._apply_milestones(referral, prog)

            # PA path / financial assistance flags.
            pa_taken = status in (
                "PRIOR_AUTH_SUBMITTED", "PRIOR_AUTH_APPROVED", "PRIOR_AUTH_DENIED",
            ) or (referral["pa_submitted_at"] is not None)
            referral["pa_required"] = pa_taken

            # Financial assistance. Most secured amounts are spread over ~180
            # days for the trend; additionally, recently activated referrals are
            # made FA-secured so the default 30-day "Financial Assistance
            # Secured" tile (metric date = coalesce(ready_to_fill_at,
            # active_therapy_at, updated_at)) shows a meaningful sum and count.
            reached_fa = status in (
                "FINANCIAL_ASSISTANCE_REVIEW", "READY_TO_FILL",
                "DELIVERY_SCHEDULED", "ACTIVE_THERAPY",
            )
            recent_active_fa = (
                status == "ACTIVE_THERAPY" and active_days_ago <= 24
            )
            fa_required = (ref_index % 3 == 0) or recent_active_fa
            referral["financial_assistance_required"] = fa_required
            secured = 0
            if fa_required and reached_fa and status != "FINANCIAL_ASSISTANCE_REVIEW":
                # Vary amounts across $1,000-$12,000.
                secured = round(1000 + (ref_index * 911) % 11001, 2)
            referral["financial_assistance_secured_amount"] = secured

            # Copay: $0-$3000.
            referral["copay_amount"] = round((ref_index * 173) % 3001 + 0.0, 2)

            # Closed only when cancelled (handled above); else NULL.
            referral["closed_at"] = None

            # updated_at = last milestone timestamp or received.
            referral["updated_at"] = self._latest_ts(referral)

            # ACTIVE_THERAPY: create + activate a therapy, hang fills off it.
            therapy_id = None
            if status == "ACTIVE_THERAPY":
                active_date = prog["milestone_date"]["ACTIVE_THERAPY"]
                therapy = self.make_therapy(
                    key=f"bulk_active_{active_seq}",
                    patient=patient,
                    status="ACTIVE",
                    start_date=active_date,
                    created_days_ago=received_days_ago,
                )
                therapy["medication_id"] = med["id"]  # align with referral med
                therapy_id = therapy["id"]
                referral["therapy_id"] = therapy_id
                self._generate_fills_for_active(therapy, patient, referral["id"],
                                                active_date, active_seq)

            self.emit_history(referral, prog)

        self.referrals.append(referral)

    def _new_referral_shell(self, ref_index, patient, owner, payer, med, disease) -> dict:
        return {
            "id": stable_uuid(f"healthrx:referral:{ref_index}"),
            "referral_number": f"RX-{10001 + ref_index}",
            "patient_id": patient["id"],
            "clinic_id": patient["clinic_id"],
            "therapy_id": None,
            "medication_id": med["id"],
            "payer_id": payer["id"],
            "owner_id": owner["id"],
            "current_status": None,
            "priority": "MEDIUM",
            "received_at": None,
            "benefits_investigation_started_at": None,
            "pa_required": False,
            "pa_submitted_at": None,
            "pa_decided_at": None,
            "financial_assistance_required": False,
            "financial_assistance_secured_amount": 0,
            "copay_amount": 0,
            "ready_to_fill_at": None,
            "delivery_scheduled_at": None,
            "active_therapy_at": None,
            "closed_at": None,
            "created_at": None,
            "updated_at": None,
            "_disease": disease,
            "_patient": patient,
            "_owner_id": owner["id"],
            "_index": ref_index,
        }

    def _populate_cancelled(self, referral: dict, received_days_ago: int, ref_index: int) -> None:
        """A cancelled referral: short happy-path prefix then ReferralCancelled."""
        # Cancel from one of a few early statuses for variety.
        cancel_from = self.CANCEL_FROM_OPTIONS[ref_index % len(self.CANCEL_FROM_OPTIONS)]
        prog = self._progression_dates(cancel_from, received_days_ago,
                                       gaps=self._varied_gaps(ref_index))
        referral["received_at"] = prog["received_at"]
        self._apply_milestones(referral, prog)
        referral["pa_required"] = referral["pa_submitted_at"] is not None
        referral["financial_assistance_required"] = (ref_index % 4 == 0)
        referral["copay_amount"] = round((ref_index * 91) % 2500 + 0.0, 2)

        # Append a CANCELLED transition a couple days after the last milestone.
        last_chain = prog["chain"][-1]
        # find day offset of last milestone
        last_date_str = last_chain[2]  # ts string
        # cancel ~2 days later but never in the future
        cancel_days_ago = max(1, received_days_ago - (received_days_ago - 1 if received_days_ago > 3 else 1))
        # Simpler: cancel 1-2 days after last milestone date.
        last_status = last_chain[1]
        last_milestone_date = prog["milestone_date"][last_status]
        cancel_date = min(DEMO_NOW - timedelta(days=1), last_milestone_date + timedelta(days=2))
        cancel_n = (DEMO_NOW - cancel_date).days
        cancel_ts = ts_days_ago(cancel_n, hour=16)
        referral["closed_at"] = cancel_ts
        prog["chain"].append((last_status, "CANCELLED", cancel_ts))
        referral["updated_at"] = cancel_ts
        self.emit_history(referral, prog)

    def _latest_ts(self, referral: dict) -> str:
        """Most recent non-null milestone timestamp (or received_at)."""
        candidates = [
            referral["active_therapy_at"],
            referral["delivery_scheduled_at"],
            referral["ready_to_fill_at"],
            referral["pa_decided_at"],
            referral["pa_submitted_at"],
            referral["benefits_investigation_started_at"],
            referral["received_at"],
        ]
        for ts in candidates:
            if ts is not None:
                return ts
        return referral["received_at"]

    # -- fills + adherence -------------------------------------------------- #

    def _generate_fills_for_active(self, therapy: dict, patient: dict,
                                   referral_id: str, active_date: date,
                                   active_seq: int) -> None:
        """Generate sequential fills engineering a spread of adherence outcomes.

        Outcome buckets cycle so the active population contains LOW, MEDIUM, and
        HIGH refill-risk therapies plus a range of PDC values.
        """
        days_supply = 30
        # bucket meaning:
        #   0,1 -> fully covered (PDC ~95-100, LOW)
        #   2   -> small gap (PDC 80-89, MEDIUM)
        #   3   -> PDC < 80 (HIGH via adherence)
        #   4   -> due within next 7 days (MEDIUM via refill-due)
        #   5   -> overdue refill, no later fill (HIGH via refill-due)

        # Number of fills back from active_date so coverage spans the window.
        # Start fills a bit after therapy start; ensure >=14 denominator days.
        start_offset = (DEMO_NOW - active_date).days  # days since start

        # Gap-heavy adherence buckets (2,3,5) need a therapy old enough to have
        # accumulated multiple fills and a >=14 day PDC window. Recently
        # activated therapies (short window) only get buckets that make sense
        # for a young therapy (well-covered or upcoming refill); this preserves
        # the engineered PDC<80 / overdue cases among the older cohort. Old
        # therapies cycle a dedicated counter so all six buckets are represented
        # regardless of how the activation schedule is ordered.
        if start_offset >= 75:
            bucket = (0, 2, 3, 5, 1, 4)[self._old_active_seq % 6]  # full spread
            self._old_active_seq += 1
        elif start_offset >= 30:
            bucket = (0, 1, 2, 4)[active_seq % 4]        # mild only
        else:
            bucket = (0, 1, 4)[active_seq % 3]           # young therapy

        fills: list[dict] = []
        if bucket in (0, 1):
            # Contiguous fills covering through ~today: last fill dispensed ~ days_supply ago.
            n_fills = max(4, min(8, start_offset // days_supply + 1))
            last_dispensed_days_ago = self.rng.choice([3, 5, 8])
            self._sequential_fills(fills, therapy, patient, referral_id,
                                   n_fills, days_supply, gap_days=0,
                                   last_dispensed_days_ago=last_dispensed_days_ago)
        elif bucket == 2:
            # Introduce a modest gap so PDC lands 80-89.
            n_fills = max(4, min(7, start_offset // days_supply + 1))
            self._sequential_fills(fills, therapy, patient, referral_id,
                                   n_fills, days_supply, gap_days=6,
                                   last_dispensed_days_ago=10)
        elif bucket == 3:
            # Bigger gaps so PDC < 80.
            n_fills = max(3, min(6, start_offset // days_supply))
            self._sequential_fills(fills, therapy, patient, referral_id,
                                   n_fills, days_supply, gap_days=15,
                                   last_dispensed_days_ago=18)
        elif bucket == 4:
            # Last fill's expected refill date is within next 7 days (MEDIUM).
            n_fills = max(4, min(7, start_offset // days_supply + 1))
            # expected_refill = dispensed + days_supply; want it ~ +4 days.
            last_dispensed_days_ago = days_supply - 4  # => expected refill in 4 days
            self._sequential_fills(fills, therapy, patient, referral_id,
                                   n_fills, days_supply, gap_days=0,
                                   last_dispensed_days_ago=last_dispensed_days_ago)
        else:  # bucket == 5
            # Overdue: last expected refill date already passed, no later fill.
            n_fills = max(3, min(6, start_offset // days_supply))
            last_dispensed_days_ago = days_supply + 8  # expected refill 8 days ago
            self._sequential_fills(fills, therapy, patient, referral_id,
                                   n_fills, days_supply, gap_days=2,
                                   last_dispensed_days_ago=last_dispensed_days_ago)

        # therapies.current_refill_due_date = latest dispensed fill's expected refill.
        if fills:
            therapy["current_refill_due_date"] = max(
                f["expected_refill_date"] for f in fills if f["status"] == "DISPENSED"
            )

    def _sequential_fills(self, out: list[dict], therapy: dict, patient: dict,
                          referral_id: str, n_fills: int, days_supply: int,
                          gap_days: int, last_dispensed_days_ago: int) -> None:
        """Create n sequential DISPENSED fills ending last_dispensed_days_ago.

        Fills step backward by ``days_supply + gap_days``; the last (most recent)
        fill is dispensed ``last_dispensed_days_ago`` before DEMO_NOW.
        """
        n_fills = max(1, n_fills)
        step = days_supply + gap_days
        # Compute dispense dates: most recent first, then reverse for fill_number.
        dispense_days = [last_dispensed_days_ago + step * k for k in range(n_fills)]
        dispense_days.reverse()  # oldest first
        therapy_start = therapy["start_date"]
        for i, d_ago in enumerate(dispense_days, start=1):
            dispensed = days_ago(d_ago)
            # Clamp so first fill isn't before therapy start.
            if therapy_start and dispensed < therapy_start:
                dispensed = therapy_start
            expected_refill = dispensed + timedelta(days=days_supply)
            self._fill_seq += 1
            out_row = {
                "id": stable_uuid(f"healthrx:fill:{self._fill_seq}"),
                "patient_id": patient["id"],
                "therapy_id": therapy["id"],
                "referral_id": referral_id,
                "fill_number": i,
                "status": "DISPENSED",
                "dispensed_at": dispensed,
                "days_supply": days_supply,
                "expected_refill_date": expected_refill,
                "created_at": ts_days_ago(d_ago, hour=11),
            }
            out.append(out_row)
            self.fills.append(out_row)

    # -- scenario A: high-copay oncology + financial assistance ------------- #

    def _build_scenario_a(self) -> None:
        patient = self.patients[0]  # Jordan Ellis, Oncology
        owner = self.care_team[0]   # Maya Patel
        payer = next(p for p in self.payers if p["payer_type"] == "Commercial")
        med = self.meds_by_disease["Oncology"][0]  # Oncora
        ref = self._new_referral_shell(0, patient, owner, payer, med, "Oncology")
        ref["current_status"] = "FINANCIAL_ASSISTANCE_REVIEW"
        ref["priority"] = "HIGH"

        prog = self._progression_dates("FINANCIAL_ASSISTANCE_REVIEW", received_days_ago=18)
        ref["received_at"] = prog["received_at"]
        self._apply_milestones(ref, prog)
        ref["pa_required"] = True
        ref["financial_assistance_required"] = True
        ref["financial_assistance_secured_amount"] = 0  # currently in review, not yet secured
        ref["copay_amount"] = 2850.00  # high copay
        ref["updated_at"] = self._latest_ts(ref)
        self.emit_history(ref, prog)
        self.referrals.append(ref)
        self._scenario_a_ref = ref

    # -- scenario B: rheumatology waiting on PA ----------------------------- #

    def _build_scenario_b(self) -> None:
        patient = self.patients[1]  # Priya Navarro, Rheumatology
        owner = self.care_team[1]   # Daniel Okeke
        payer = next(p for p in self.payers if p["payer_type"] == "Commercial")
        med = self.meds_by_disease["Rheumatology"][0]  # Immunza
        ref = self._new_referral_shell(1, patient, owner, payer, med, "Rheumatology")
        ref["current_status"] = "PRIOR_AUTH_SUBMITTED"
        ref["priority"] = "HIGH"

        # received 9d ago, benefits 7d, pa_submitted 5d ago, pa_decided NULL.
        ref["received_at"] = ts_days_ago(9, hour=13)
        ref["benefits_investigation_started_at"] = ts_days_ago(7, hour=14)
        ref["pa_submitted_at"] = ts_days_ago(5, hour=14)
        ref["pa_decided_at"] = None
        ref["pa_required"] = True
        ref["financial_assistance_required"] = False
        ref["copay_amount"] = 75.00
        ref["updated_at"] = ref["pa_submitted_at"]

        prog = {"chain": [
            (None, "ELIGIBILITY_IDENTIFIED", ts_days_ago(9, hour=13)),
            ("ELIGIBILITY_IDENTIFIED", "BENEFITS_INVESTIGATION", ts_days_ago(7, hour=14)),
            ("BENEFITS_INVESTIGATION", "PRIOR_AUTH_SUBMITTED", ts_days_ago(5, hour=14)),
        ]}
        self.emit_history(ref, prog)
        self.referrals.append(ref)
        self._scenario_b_ref = ref

    # -- scenario C: MS active w/ upcoming refill risk (unresolved) --------- #

    def _build_scenario_c(self) -> None:
        patient = self.patients[2]  # Marlowe Okafor, MS
        owner = self.care_team[2]   # Sofia Reyes
        payer = self.payers[0]
        med = self.meds_by_disease["Multiple sclerosis"][0]  # Neurosphere
        ref = self._new_referral_shell(2, patient, owner, payer, med, "Multiple sclerosis")
        ref["current_status"] = "ACTIVE_THERAPY"
        ref["priority"] = "MEDIUM"

        # Active therapy started ~120 days ago, full happy path.
        active_days_ago = 120
        ref["received_at"] = ts_days_ago(active_days_ago + 12, hour=13)
        ref["benefits_investigation_started_at"] = ts_days_ago(active_days_ago + 10, hour=14)
        ref["pa_submitted_at"] = ts_days_ago(active_days_ago + 8, hour=14)
        ref["pa_decided_at"] = ts_days_ago(active_days_ago + 5, hour=14)
        ref["ready_to_fill_at"] = ts_days_ago(active_days_ago + 3, hour=14)
        ref["delivery_scheduled_at"] = ts_days_ago(active_days_ago + 1, hour=14)
        ref["active_therapy_at"] = ts_days_ago(active_days_ago, hour=14)
        ref["pa_required"] = True
        ref["financial_assistance_required"] = False
        ref["copay_amount"] = 45.00
        ref["updated_at"] = ref["active_therapy_at"]

        active_date = days_ago(active_days_ago)
        therapy = self.make_therapy(
            key="scenario_c",
            patient=patient,
            status="ACTIVE",
            start_date=active_date,
            created_days_ago=active_days_ago + 12,
        )
        therapy["medication_id"] = med["id"]
        ref["therapy_id"] = therapy["id"]

        # Fills: well-covered historically, last fill's expected refill is in ~5
        # days (upcoming). PDC stays healthy so risk is driven by the unresolved
        # outreach condition, not adherence.
        days_supply = 30
        # last dispensed 25 days ago -> expected refill in 5 days (upcoming).
        self._sequential_fills([], therapy, patient, ref["id"],
                               n_fills=4, days_supply=days_supply, gap_days=0,
                               last_dispensed_days_ago=25)
        # set current_refill_due_date to the latest dispensed expected refill.
        therapy_fills = [f for f in self.fills if f["therapy_id"] == therapy["id"]]
        therapy["current_refill_due_date"] = max(f["expected_refill_date"] for f in therapy_fills)

        prog = {"chain": [
            (None, "ELIGIBILITY_IDENTIFIED", ts_days_ago(active_days_ago + 12, hour=13)),
            ("ELIGIBILITY_IDENTIFIED", "BENEFITS_INVESTIGATION", ts_days_ago(active_days_ago + 10, hour=14)),
            ("BENEFITS_INVESTIGATION", "PRIOR_AUTH_SUBMITTED", ts_days_ago(active_days_ago + 8, hour=14)),
            ("PRIOR_AUTH_SUBMITTED", "PRIOR_AUTH_APPROVED", ts_days_ago(active_days_ago + 5, hour=14)),
            ("PRIOR_AUTH_APPROVED", "READY_TO_FILL", ts_days_ago(active_days_ago + 3, hour=14)),
            ("READY_TO_FILL", "DELIVERY_SCHEDULED", ts_days_ago(active_days_ago + 1, hour=14)),
            ("DELIVERY_SCHEDULED", "ACTIVE_THERAPY", ts_days_ago(active_days_ago, hour=14)),
        ]}
        self.emit_history(ref, prog)
        self.referrals.append(ref)
        self._scenario_c_ref = ref
        self._scenario_c_therapy = therapy

        # Two unsuccessful outreach events in the last 14 days, UNRESOLVED
        # (no REACHED follow-up and no adherence/care-coordination intervention).
        for k, (d_ago, outcome, channel) in enumerate([
            (10, "NO_ANSWER", "PHONE"),
            (6, "LEFT_MESSAGE", "PHONE"),
            (3, "NEEDS_FOLLOW_UP", "SMS"),
        ]):
            self._outreach_seq += 1
            self.outreach_events.append({
                "id": stable_uuid(f"healthrx:outreach:scenario_c:{k}"),
                "patient_id": patient["id"],
                "referral_id": ref["id"],
                "owner_id": owner["id"],
                "channel": channel,
                "outcome": outcome,
                "notes": "Attempted refill reminder; no resolution yet.",
                "occurred_at": ts_days_ago(d_ago, hour=15),
                "created_at": ts_days_ago(d_ago, hour=15),
            })

    # -- scenario D: GI patient with open MISSING_LAB task ------------------ #

    def _build_scenario_d(self) -> None:
        patient = self.patients[3]  # Felix Lindqvist, GI
        owner = self.care_team[4]   # Aisha Khan (Clinical Pharmacist)
        payer = self.payers[1]
        med = self.meds_by_disease["Gastroenterology"][0]  # Gastronib
        ref = self._new_referral_shell(3, patient, owner, payer, med, "Gastroenterology")
        ref["current_status"] = "ACTIVE_THERAPY"
        ref["priority"] = "MEDIUM"

        active_days_ago = 80
        ref["received_at"] = ts_days_ago(active_days_ago + 11, hour=13)
        ref["benefits_investigation_started_at"] = ts_days_ago(active_days_ago + 9, hour=14)
        ref["pa_submitted_at"] = ts_days_ago(active_days_ago + 7, hour=14)
        ref["pa_decided_at"] = ts_days_ago(active_days_ago + 4, hour=14)
        ref["ready_to_fill_at"] = ts_days_ago(active_days_ago + 2, hour=14)
        ref["delivery_scheduled_at"] = ts_days_ago(active_days_ago + 1, hour=14)
        ref["active_therapy_at"] = ts_days_ago(active_days_ago, hour=14)
        ref["pa_required"] = True
        ref["financial_assistance_required"] = False
        ref["copay_amount"] = 120.00
        ref["updated_at"] = ref["active_therapy_at"]

        active_date = days_ago(active_days_ago)
        therapy = self.make_therapy(
            key="scenario_d",
            patient=patient,
            status="ACTIVE",
            start_date=active_date,
            created_days_ago=active_days_ago + 11,
        )
        therapy["medication_id"] = med["id"]
        ref["therapy_id"] = therapy["id"]
        # Well-covered fills => LOW adherence risk; the demo focus is the task.
        self._sequential_fills([], therapy, patient, ref["id"],
                               n_fills=3, days_supply=30, gap_days=0,
                               last_dispensed_days_ago=6)
        therapy_fills = [f for f in self.fills if f["therapy_id"] == therapy["id"]]
        therapy["current_refill_due_date"] = max(f["expected_refill_date"] for f in therapy_fills)

        prog = {"chain": [
            (None, "ELIGIBILITY_IDENTIFIED", ts_days_ago(active_days_ago + 11, hour=13)),
            ("ELIGIBILITY_IDENTIFIED", "BENEFITS_INVESTIGATION", ts_days_ago(active_days_ago + 9, hour=14)),
            ("BENEFITS_INVESTIGATION", "PRIOR_AUTH_SUBMITTED", ts_days_ago(active_days_ago + 7, hour=14)),
            ("PRIOR_AUTH_SUBMITTED", "PRIOR_AUTH_APPROVED", ts_days_ago(active_days_ago + 4, hour=14)),
            ("PRIOR_AUTH_APPROVED", "READY_TO_FILL", ts_days_ago(active_days_ago + 2, hour=14)),
            ("READY_TO_FILL", "DELIVERY_SCHEDULED", ts_days_ago(active_days_ago + 1, hour=14)),
            ("DELIVERY_SCHEDULED", "ACTIVE_THERAPY", ts_days_ago(active_days_ago, hour=14)),
        ]}
        self.emit_history(ref, prog)
        self.referrals.append(ref)
        self._scenario_d_ref = ref

        # The open MISSING_LAB task due near DEMO_NOW.
        self._task_seq += 1
        self.tasks.append({
            "id": stable_uuid(f"healthrx:task:scenario_d"),
            "patient_id": patient["id"],
            "referral_id": ref["id"],
            "owner_id": owner["id"],
            "type": "MISSING_LAB",
            "status": "OPEN",
            "priority": "HIGH",
            "title": "Obtain baseline hepatic panel before next dispense",
            "description": "Lab results required prior to releasing the next fill of Gastronib.",
            "due_at": ts_days_ago(-1, hour=17),  # due tomorrow (near DEMO_NOW)
            "completed_at": None,
            "created_at": ts_days_ago(4, hour=9),
        })

    # -- notes / tasks / outreach / interventions (bulk) -------------------- #

    def build_referral_notes(self) -> None:
        note_bodies = [
            "Patient prefers afternoon calls.",
            "Confirmed shipping address with caregiver.",
            "Awaiting clinical documentation from prescriber.",
            "Discussed copay assistance options; patient interested.",
            "Verified insurance eligibility for specialty benefit.",
            "Patient reports mild nausea; counseled on timing with food.",
            "Coordinating delivery window with patient's work schedule.",
            "Left voicemail requesting callback for benefits review.",
            "Prescriber office faxed updated prior-auth form.",
            "Confirmed refrigeration requirements for delivery.",
        ]
        # >=40 notes spread across referrals (skip cancelled where possible).
        eligible = [r for r in self.referrals if r["current_status"] != "CANCELLED"]
        target = 46
        for i in range(target):
            ref = eligible[(i * 3) % len(eligible)]
            author = self.care_team[i % len(self.care_team)]
            self._note_seq += 1
            # Note time within [received_at .. updated_at] window; use a recent offset.
            d_ago = 1 + (i * 5) % 60
            self.referral_notes.append({
                "id": stable_uuid(f"healthrx:note:{self._note_seq}"),
                "referral_id": ref["id"],
                "author_id": author["id"],
                "body": note_bodies[i % len(note_bodies)],
                "created_at": ts_days_ago(d_ago, hour=10, minute=(i * 7) % 60),
            })

    def build_tasks(self) -> None:
        """>=80 tasks across owners, including overdue OPEN/IN_PROGRESS ones."""
        task_types = [
            "MISSING_LAB", "PRIOR_AUTH_RENEWAL", "PATIENT_CONTACT",
            "FINANCIAL_ASSISTANCE", "REFILL_FOLLOW_UP", "CLINICAL_REVIEW",
        ]
        titles = {
            "MISSING_LAB": "Collect outstanding lab work",
            "PRIOR_AUTH_RENEWAL": "Submit prior authorization renewal",
            "PATIENT_CONTACT": "Reach patient to confirm next steps",
            "FINANCIAL_ASSISTANCE": "Follow up on financial assistance application",
            "REFILL_FOLLOW_UP": "Confirm upcoming refill with patient",
            "CLINICAL_REVIEW": "Clinical review of therapy progress",
        }
        # Scenario D already added one MISSING_LAB task; add 84 more for >=80.
        target = 84
        non_cancelled = [r for r in self.referrals if r["current_status"] != "CANCELLED"]
        for i in range(target):
            ttype = task_types[i % len(task_types)]
            owner = self.care_team[i % len(self.care_team)]
            ref = non_cancelled[(i * 5) % len(non_cancelled)]
            patient = ref["_patient"]

            # Status mix: many OPEN/IN_PROGRESS, some COMPLETED/CANCELLED.
            mod = i % 5
            if mod in (0, 1):
                status = "OPEN"
            elif mod == 2:
                status = "IN_PROGRESS"
            elif mod == 3:
                status = "COMPLETED"
            else:
                status = "CANCELLED"

            # Due dates: alternate overdue (past) and upcoming/future.
            if status in ("OPEN", "IN_PROGRESS"):
                if i % 3 == 0:
                    due_d_ago = 2 + (i % 10)   # overdue (past, < DEMO_NOW)
                    completed_at = None
                elif i % 3 == 1:
                    due_d_ago = -(2 + (i % 5))  # due soon (future, within days)
                    completed_at = None
                else:
                    due_d_ago = -(8 + (i % 20))  # due later
                    completed_at = None
            elif status == "COMPLETED":
                due_d_ago = 5 + (i % 15)
                completed_at = ts_days_ago(max(1, due_d_ago - 1), hour=16)
            else:  # CANCELLED
                due_d_ago = 10 + (i % 20)
                completed_at = None

            self._task_seq += 1
            priority = PRIORITIES[i % len(PRIORITIES)]
            self.tasks.append({
                "id": stable_uuid(f"healthrx:task:{self._task_seq}"),
                "patient_id": patient["id"],
                "referral_id": ref["id"],
                "owner_id": owner["id"],
                "type": ttype,
                "status": status,
                "priority": priority,
                "title": titles[ttype],
                "description": f"{titles[ttype]} for referral {ref['referral_number']}.",
                "due_at": ts_days_ago(due_d_ago, hour=17),
                "completed_at": completed_at,
                "created_at": ts_days_ago(20 + (i % 40), hour=9),
            })

    def build_outreach_events(self) -> None:
        """>=60 outreach events. Scenario C already added 3 unresolved ones."""
        channels = ["PHONE", "SMS", "EMAIL", "PORTAL"]
        outcomes = ["REACHED", "LEFT_MESSAGE", "NO_ANSWER", "DECLINED", "NEEDS_FOLLOW_UP"]
        notes = [
            "Confirmed receipt of shipment.",
            "Reviewed dosing schedule with patient.",
            "Patient confirmed upcoming refill date.",
            "Left message regarding pending paperwork.",
            "No answer; will retry tomorrow.",
            "Patient declined home delivery this cycle.",
            "Follow-up needed on side-effect report.",
        ]
        target = 64
        # Scenario C must stay UNRESOLVED (no REACHED outreach after its
        # unsuccessful events), so exclude its patient from bulk outreach.
        scenario_c_patient = self._scenario_c_ref["patient_id"]
        # Prefer patients with active therapies for richer workbench timelines.
        active_refs = [r for r in self.referrals
                       if r["current_status"] == "ACTIVE_THERAPY"
                       and r["patient_id"] != scenario_c_patient]
        all_refs = [r for r in self.referrals
                    if r["current_status"] != "CANCELLED"
                    and r["patient_id"] != scenario_c_patient]
        for i in range(target):
            # Mix active-therapy referrals and general ones.
            ref = active_refs[i % len(active_refs)] if i % 2 == 0 else all_refs[(i * 3) % len(all_refs)]
            patient = ref["_patient"]
            owner = self.care_team[i % len(self.care_team)]
            outcome = outcomes[i % len(outcomes)]
            channel = channels[i % len(channels)]
            d_ago = 2 + (i * 4) % 150
            self._outreach_seq += 1
            self.outreach_events.append({
                "id": stable_uuid(f"healthrx:outreach:{self._outreach_seq}"),
                "patient_id": patient["id"],
                "referral_id": ref["id"],
                "owner_id": owner["id"],
                "channel": channel,
                "outcome": outcome,
                "notes": notes[i % len(notes)],
                "occurred_at": ts_days_ago(d_ago, hour=15, minute=(i * 11) % 60),
                "created_at": ts_days_ago(d_ago, hour=15, minute=(i * 11) % 60),
            })

    def build_clinical_interventions(self) -> None:
        """>=30 interventions. Do NOT add any for scenario C (keep it unresolved)."""
        types = [
            "ADHERENCE_COUNSELING", "SIDE_EFFECT_MANAGEMENT", "DOSE_CLARIFICATION",
            "LAB_MONITORING", "CARE_COORDINATION",
        ]
        summaries = {
            "ADHERENCE_COUNSELING": "Reviewed refill schedule and adherence barriers with patient.",
            "SIDE_EFFECT_MANAGEMENT": "Counseled on managing injection-site reactions.",
            "DOSE_CLARIFICATION": "Clarified titration schedule with prescriber.",
            "LAB_MONITORING": "Scheduled follow-up labs and flagged results for review.",
            "CARE_COORDINATION": "Coordinated benefits and delivery logistics across team.",
        }
        target = 34
        # Exclude the scenario C patient entirely: an ADHERENCE_COUNSELING or
        # CARE_COORDINATION intervention in the last 14 days would resolve its
        # outreach-driven HIGH risk, which must stay unresolved for the demo.
        scenario_c_patient = self._scenario_c_ref["patient_id"]
        active_refs = [r for r in self.referrals
                       if r["current_status"] == "ACTIVE_THERAPY"
                       and r["patient_id"] != scenario_c_patient]
        for i in range(target):
            ref = active_refs[i % len(active_refs)]
            patient = ref["_patient"]
            owner = self.care_team[i % len(self.care_team)]
            itype = types[i % len(types)]
            d_ago = 3 + (i * 5) % 140
            self._intervention_seq += 1
            self.clinical_interventions.append({
                "id": stable_uuid(f"healthrx:intervention:{self._intervention_seq}"),
                "patient_id": patient["id"],
                "referral_id": ref["id"],
                "owner_id": owner["id"],
                "intervention_type": itype,
                "summary": summaries[itype],
                "occurred_at": ts_days_ago(d_ago, hour=13, minute=(i * 9) % 60),
                "created_at": ts_days_ago(d_ago, hour=13, minute=(i * 9) % 60),
            })

    # -- orchestration ------------------------------------------------------ #

    def build_all(self) -> None:
        self.build_clinics()
        self.build_payers()
        self.build_care_team()
        self.build_medications()
        self.build_patients()
        self.build_referrals()       # creates therapies + fills for ACTIVE
        self.build_referral_notes()
        self.build_tasks()
        self.build_outreach_events()
        self.build_clinical_interventions()


# --------------------------------------------------------------------------- #
# SQL emission
# --------------------------------------------------------------------------- #

# (table, columns) emitted in strict FK-dependency order.
TABLE_COLUMNS = [
    ("clinics", ["id", "name", "region", "created_at"]),
    ("payers", ["id", "name", "payer_type", "created_at"]),
    ("care_team_members", ["id", "display_name", "role", "email", "active", "created_at"]),
    ("medications", ["id", "name", "disease_state", "route", "limited_distribution", "active", "created_at"]),
    ("patients", ["id", "demo_mrn", "first_name", "last_name", "date_of_birth",
                  "disease_state", "clinic_id", "primary_owner_id", "payer_id", "created_at"]),
    ("therapies", ["id", "patient_id", "medication_id", "diagnosis", "disease_state",
                   "status", "start_date", "end_date", "current_refill_due_date", "created_at"]),
    ("referrals", ["id", "referral_number", "patient_id", "clinic_id", "therapy_id",
                   "medication_id", "payer_id", "owner_id", "current_status", "priority",
                   "received_at", "benefits_investigation_started_at", "pa_required",
                   "pa_submitted_at", "pa_decided_at", "financial_assistance_required",
                   "financial_assistance_secured_amount", "copay_amount", "ready_to_fill_at",
                   "delivery_scheduled_at", "active_therapy_at", "closed_at",
                   "created_at", "updated_at"]),
    ("referral_status_history", ["id", "referral_id", "from_status", "to_status",
                                 "changed_at", "changed_by_id", "note", "phase2_event_type"]),
    ("referral_notes", ["id", "referral_id", "author_id", "body", "created_at"]),
    ("tasks", ["id", "patient_id", "referral_id", "owner_id", "type", "status",
               "priority", "title", "description", "due_at", "completed_at", "created_at"]),
    ("outreach_events", ["id", "patient_id", "referral_id", "owner_id", "channel",
                         "outcome", "notes", "occurred_at", "created_at"]),
    ("clinical_interventions", ["id", "patient_id", "referral_id", "owner_id",
                                "intervention_type", "summary", "occurred_at", "created_at"]),
    ("fills", ["id", "patient_id", "therapy_id", "referral_id", "fill_number", "status",
               "dispensed_at", "days_supply", "expected_refill_date", "created_at"]),
]


def render_sql(b: SeedBuilder) -> str:
    rows_by_table = {
        "clinics": b.clinics,
        "payers": b.payers,
        "care_team_members": b.care_team,
        "medications": b.medications,
        "patients": b.patients,
        "therapies": b.therapies,
        "referrals": b.referrals,
        "referral_status_history": b.status_history,
        "referral_notes": b.referral_notes,
        "tasks": b.tasks,
        "outreach_events": b.outreach_events,
        "clinical_interventions": b.clinical_interventions,
        "fills": b.fills,
    }

    lines: list[str] = []
    lines.append("-- HealthRx Phase 1 deterministic seed data.")
    lines.append("-- GENERATED FILE. Do not edit by hand; regenerate with:")
    lines.append("--   python3 scripts/generate_seed.py")
    lines.append(f"-- Anchored at application clock DEMO_NOW = {DEMO_NOW.isoformat()}.")
    lines.append("-- UUIDs are uuid5-derived and all timestamps are fixed offsets from DEMO_NOW,")
    lines.append("-- so rebuilds are byte-identical. See data-model.md / metric-definitions.md.")
    lines.append("")

    for table, columns in TABLE_COLUMNS:
        rows = rows_by_table[table]
        lines.append(f"-- {table} ({len(rows)} rows)")
        for row in rows:
            lines.append(insert(table, columns, row))
        lines.append("")

    return "\n".join(lines)


# --------------------------------------------------------------------------- #
# Summary
# --------------------------------------------------------------------------- #


def print_summary(b: SeedBuilder) -> None:
    counts = [
        ("clinics", b.clinics),
        ("payers", b.payers),
        ("care_team_members", b.care_team),
        ("medications", b.medications),
        ("patients", b.patients),
        ("therapies", b.therapies),
        ("referrals", b.referrals),
        ("referral_status_history", b.status_history),
        ("referral_notes", b.referral_notes),
        ("tasks", b.tasks),
        ("outreach_events", b.outreach_events),
        ("clinical_interventions", b.clinical_interventions),
        ("fills", b.fills),
    ]
    print("Per-table row counts:")
    for name, rows in counts:
        print(f"  {name:<26} {len(rows)}")

    print("\nReferrals per status:")
    status_counts = Counter(r["current_status"] for r in b.referrals)
    for status in [
        "ELIGIBILITY_IDENTIFIED", "BENEFITS_INVESTIGATION", "PRIOR_AUTH_SUBMITTED",
        "PRIOR_AUTH_APPROVED", "PRIOR_AUTH_DENIED", "FINANCIAL_ASSISTANCE_REVIEW",
        "READY_TO_FILL", "DELIVERY_SCHEDULED", "ACTIVE_THERAPY", "CANCELLED",
    ]:
        print(f"  {status:<28} {status_counts.get(status, 0)}")

    active_therapies = sum(1 for t in b.therapies if t["status"] == "ACTIVE")
    print(f"\nACTIVE therapies: {active_therapies}")

    overdue = sum(
        1 for t in b.tasks
        if t["status"] in ("OPEN", "IN_PROGRESS") and t["due_at"] and t["due_at"] < ts_days_ago(0)
    )
    print(f"Overdue OPEN/IN_PROGRESS tasks: {overdue}")

    print("\nDemo scenarios:")
    print(f"  A High-copay Oncology FA review : {b.patients[0]['first_name']} "
          f"{b.patients[0]['last_name']} -> {b._scenario_a_ref['referral_number']}")
    print(f"  B Rheumatology waiting on PA    : {b.patients[1]['first_name']} "
          f"{b.patients[1]['last_name']} -> {b._scenario_b_ref['referral_number']}")
    print(f"  C MS active w/ refill risk      : {b.patients[2]['first_name']} "
          f"{b.patients[2]['last_name']} -> {b._scenario_c_ref['referral_number']}")
    print(f"  D GI open MISSING_LAB task      : {b.patients[3]['first_name']} "
          f"{b.patients[3]['last_name']} -> {b._scenario_d_ref['referral_number']}")


# --------------------------------------------------------------------------- #
# Entry point
# --------------------------------------------------------------------------- #

OUTPUT_PATH = (
    Path(__file__).resolve().parent.parent
    / "backend" / "src" / "main" / "resources" / "db" / "migration" / "V2__seed_data.sql"
)


def main() -> None:
    builder = SeedBuilder()
    builder.build_all()
    sql = render_sql(builder)
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(sql)
    print(f"Wrote {OUTPUT_PATH}")
    print()
    print_summary(builder)


if __name__ == "__main__":
    main()
