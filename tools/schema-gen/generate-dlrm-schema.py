#!/usr/bin/env python3
"""Generate the LIBRA DLRM JSON Schema from the DLRM CP Migration Data Schema workbook.

Reads the 'Libra Case - Min Data' tab and emits a draft-04 JSON Schema shaped like the canonical
runtime schema: a `migratedCase` envelope over caseDetails / hearings / defendants[], with shared
`definitions`.

TWO FILES come out of this. The schema is written to be SHARED — with the LIBRA extract team, the
func-app owner, anyone building against it — so it carries no trace of the workbook: no sheet names,
no row numbers, no Format cells, no internal notes, and it follows the live XHIBIT schema's shape
and conventions down to definition order. Everything the workbook says goes to the provenance
sidecar beside it (`<schema>.provenance.json`), which is internal and is what
build-schema-impact.py reads.

The workbook is NOT the naming or typing authority. It is read alongside the two live contracts —
the flattened canonical schema (--reference) and the live XHIBIT schema (--xhibit) — and wherever
the contract already models a field, the contract wins:

  * NAMES — the sheet's own field name is mapped onto the contract's property name (CONTRACT_ALIASES,
    every target checked against the live XHIBIT schema at generation time).
  * DEFINITIONS — a field the contract already declares takes the contract's fragment verbatim
    ($ref/type/maxLength/pattern/enum), not the derivation from the sheet's Format cell. Where the
    two disagree, the contract is emitted and the sheet's reading goes to the sidecar and the report,
    classified as a conflict, an unstated Format cell, or the contract merely being looser.
  * SHAPE — definitions the contract reuses in several places are reused here too (MERGED_CONTAINERS),
    so `required` on a shared definition is the intersection of its contributors.
  * `required` is the one thing that stays sheet-derived: it is LIBRA's own mandatoriness.

Only LIBRA is generated. XHIBIT is already in production — its live contract is the func-app's
own schema resources (stagingdlrm-azure-functions/src/main/resources) plus the canonical
stagingdlrm-domain-value-schema module, both read directly by the other scripts here. There is
no reason to re-derive it from the workbook.

Output goes to the current directory unless --out-dir (or an explicit --out) is given, so an
ad-hoc run never writes into the repo. ./tools/schema-gen/regenerate.sh refreshes the committed
copies under docs/analysis/libra-ingestion/.

Python 3 standard library only — an .xlsx is a zip of XML, so no openpyxl/pandas needed
(consistent with tools/reconciliation/, which is also stdlib-only).

Design decisions baked in (see docs/analysis/libra-ingestion-analysis.md):

* `required` is the INTERSECTION of the sheet's case-type columns — a field is required only
  when every mark it carries is `M`. A BLANK cell means "not stated for this case type" and is
  ignored; any `O`/`CM`/`N/A` disqualifies. Per-case-type variance ("Mandatory: Postal
  Requisition. Not applicable: Summons") is recorded in each field's description instead, because
  a single schema cannot express it and enforcing it belongs in the source-system validation-rules
  strategy. `CM` (conditionally mandatory) is treated as optional, with the condition in the
  description.
* The LIBRA tab carries four case-type columns (SJP Referral / Summons / Charge / Postal
  Requisition); a field is required only where all four that are populated say `M`.
* Sheet fields with no equivalent in the reference schema are still emitted, tagged in their
  description and listed in the report.
* Shared primitives (date, uuid, phone, email, postcode pattern, ...) are COPIED from the
  reference schema at generation time rather than re-typed here, so the regexes cannot drift.
  The reference is the flattened canonical schema, so the primitives come from what stagingDLRM
  actually enforces today — run flatten-canonical-schema.py first (regenerate.sh does).
* Contract lookup is per CONTAINER, not by bare property name: `officerInCase.surname` is a
  LIBRA-only field even though `personalInformation.surname` exists, and the parent-guardian
  block resolves against the contract's own oneOf branches instead of reading as unmodelled.

Usage:
    python3 tools/schema-gen/generate-dlrm-schema.py            # write + report
    python3 tools/schema-gen/generate-dlrm-schema.py --dry-run  # report only
    # diff the generated schema against any existing one:
    python3 tools/schema-gen/generate-dlrm-schema.py --compare path/to/schema.json
"""

import argparse
import json
import re
import sys
import zipfile
from collections import OrderedDict
from pathlib import Path
from xml.etree import ElementTree as ET

REPO = Path(__file__).resolve().parents[2]
DEFAULT_WORKBOOK = REPO / "docs/analysis/libra-ingestion/DLRM - CP Migration Data Schema V0.13.xlsx"
DEFAULT_REFERENCE = (REPO / "docs/analysis/libra-ingestion/schema/canonical"
                     / "staging-dlrm-canonical-flattened.json")
DEFAULT_XHIBIT = (REPO / "docs/analysis/libra-ingestion/schema/xhibit"
                  / "dlrm-xhibit-0.12.json")

# The sheet documents initiation codes (LIBRA C/S/Q/J). Emitted as plain
# strings with the values in the description rather than an `enum`, because an over-tight enum
# compiles into a closed Java enum downstream and a schema rejection is terminal, not retryable
# (libra-ingestion-analysis.md 3.3, 4). Flip to True to emit enums instead.
EMIT_CODE_ENUMS = False

# Primitive definitions copied verbatim from the reference schema (never re-typed here).
COPIED_DEFINITIONS = ["date", "datePattern", "uuid", "phone", "email", "migrationSourceSystemName"]

# The schema is the shareable artefact; everything the workbook says about a field lands here.
PROVENANCE_SUFFIX = ".provenance.json"

# The envelope property both contracts hang the case model off.
REFERENCE_ROOT_PROPERTY = "migratedCase"

NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
REL_NS = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"

TIME_PATTERN = r"^(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d$"

# Fields the reference schema constrains as a 7-character CJS OU code.
OUCODE_PROPERTIES = {"prosecutingAuthority", "originatingOrganisation", "cpsOrganisation",
                     "courtHearingLocation", "policeWorkerLocationCode", "sendingCourt",
                     "receivingCourt", "convictingCourtCode"}

# Property names whose fragment is a generator CONVENTION rather than a reading of the sheet's
# Format cell (see by_property()). A difference between one of these and the contract is not a
# workbook signal, so it is not reported as one.
CONVENTION_PROPERTIES = {"postcode", "primaryEmail", "secondaryEmail", "emailAddress1",
                         "emailAddress2", "work", "home", "mobile", "workTelephoneNumber",
                         "mobileTelephoneNumber", "homeTelephoneNumber",
                         "telephoneNumberBusiness", "companyTelephoneNumber",
                         "organisationTelephoneNumber", "faxNumber",
                         "migrationSourceSystemName", "listedOffences", "aliasForCorporate",
                         "startDate"}


# --------------------------------------------------------------------------------------
# Target structure: containers become `definitions` entries in the emitted schema
#   key: (definition name, parent container key, property on parent, array?, strict?)
# `strict` mirrors whether the reference schema sets additionalProperties:false there.
# --------------------------------------------------------------------------------------

# Containers that do NOT get a definition of their own: their fields are unioned into another
# container's, because the live contract reuses one definition in both places and this schema
# follows its shape. `required` on a shared definition is the INTERSECTION of its contributors — a
# field is mandatory only where every section that supplies it says so, the same rule the generator
# already applies across the sheet's case-type columns. Each intersection that actually drops a
# `required` is reported, never silent.
MERGED_CONTAINERS = {
    "pgPersonalInfo":   "personalInfo",
    "pgContactDetails": "contactDetails",
    "pgAddress":        "address",
    "pgOrgAddress":     "address",
    "officerAddress":   "address",
}

# The two parent-guardian containers render as one definition holding a oneOf of two inline
# branches, which is how both live contracts model it.
PG_BRANCHES = ("pgPerson", "pgOrg")
PG_DEFINITION = "parentGuardianInformation"

# Containers whose JSONPath parent cannot be read off CONTAINERS: pgOrg is the second branch of the
# same oneOf pgPerson attaches through, so both sit at the same path.
PATH_PARENT = {"pgOrg": ("individual", "parentGuardianInformation")}

# Definition-level descriptions for the definitions the live contract does not have. Every other
# definition takes the contract's own wording, so the two files read alike.
LIBRA_ONLY_DESCRIPTIONS = {"officerInCase": "Officer in Case"}

CONTAINERS = OrderedDict([
    ("caseDetails",       ("caseDetails", None, "caseDetails", False, True)),
    ("prosecutor",        ("prosecutor", "caseDetails", "prosecutor", False, True)),
    ("caseMarkers",       ("caseMarkers", "caseDetails", "caseMarkers", True, True)),
    ("migrationSource",   ("migrationSourceSystem", None, "migrationSourceSystem", False, True)),
    ("hearing",           ("hearing", None, "hearings", True, False)),
    ("weekCommencing",    ("weekCommencingDate", "hearing", "weekCommencingDate", False, True)),
    ("listedDefendant",   ("listedDefendant", "hearing", "listedDefendants", True, False)),
    ("defendant",         ("defendant", None, "defendants", True, True)),
    ("address",           ("address", "defendant", "address", False, True)),
    ("individual",        ("individual", "defendant", "individual", False, False)),
    ("personalInfo",      ("personalInformation", "individual", "personalInformation", False, False)),
    ("contactDetails",    ("contactDetails", "personalInfo", "contactDetails", False, True)),
    ("selfDefinedInfo",   ("selfDefinedInformation", "individual", "selfDefinedInformation", False, True)),
    ("individualAlias",   ("individualAlias", "defendant", "individualAliases", True, True)),
    ("offence",           ("offence", "defendant", "offences", True, False)),
    ("alcohol",           ("alcoholRelatedOffence", "offence", "alcoholRelatedOffence", False, True)),
    ("plea",              ("plea", "offence", "plea", False, True)),
    ("verdict",           ("verdict", "offence", "verdict", False, True)),
    ("allocation",        ("allocationDecision", "offence", "allocationDecision", False, True)),
    # Parent guardian and the officer's address reuse the shared personalInformation / address /
    # contactDetails definitions, as both live contracts do — see MERGED_CONTAINERS. These keys
    # still exist because they are how the sheet's sections route, and because the ATTACHMENT and
    # its mandatoriness are derived per section; only the definition is shared.
    ("pgPerson",          ("parentGuardianPerson", "individual", "parentGuardianInformation", False, True)),
    ("pgPersonalInfo",    ("parentGuardianPersonalInformation", "pgPerson", "personalInformation", False, False)),
    ("pgContactDetails",  ("parentGuardianContactDetails", "pgPersonalInfo", "contactDetails", False, True)),
    ("pgAddress",         ("parentGuardianAddress", "pgPersonalInfo", "address", False, True)),
    ("pgOrg",             ("parentGuardianOrganisation", None, None, False, True)),
    ("pgOrgAddress",      ("parentGuardianOrganisationAddress", "pgOrg", "address", False, True)),
    # LIBRA-only section, no counterpart in the canonical schema.
    ("officerInCase",     ("officerInCase", None, "officerInCase", False, True)),
    ("officerAddress",    ("officerInCaseAddress", "officerInCase", "address", False, True)),
])

# --------------------------------------------------------------------------------------
# Where each container lives in the two live contracts: (definition name, oneOf discriminator).
# The discriminator is a property that must appear in the branch, so the parent-guardian lookup
# does not depend on the order of the contract's oneOf. None = the definition's own properties.
#
# `None` for the whole entry means the container has NO counterpart in either contract: every
# field inside it is LIBRA-only, even where the property name (surname, address1, ...) coincides
# with one the contract uses elsewhere.
# --------------------------------------------------------------------------------------

LIVE_DEFINITIONS = {
    "caseDetails":      ("caseDetails", None),
    "prosecutor":       ("prosecutor", None),
    "caseMarkers":      ("caseMarkers", None),
    "migrationSource":  ("migrationSourceSystem", None),
    "hearing":          ("hearing", None),
    "weekCommencing":   ("weekCommencingDate", None),
    "listedDefendant":  ("listedDefendant", None),
    "defendant":        ("defendant", None),
    "address":          ("address", None),
    "individual":       ("individual", None),
    "personalInfo":     ("personalInformation", None),
    "contactDetails":   ("contactDetails", None),
    "selfDefinedInfo":  ("selfDefinedInformation", None),
    "individualAlias":  ("individualAlias", None),
    "offence":          ("offence", None),
    "alcohol":          ("alcoholRelatedOffence", None),
    "plea":             ("plea", None),
    "verdict":          ("verdict", None),
    "allocation":       ("allocationDecision", None),
    # The contract models parent guardian as one oneOf(person | organisation); the generator gives
    # each side its own definition, so both resolve against the same contract definition.
    "pgPerson":         ("parentGuardianInformation", "personalInformation"),
    "pgPersonalInfo":   ("personalInformation", None),
    "pgContactDetails": ("contactDetails", None),
    "pgAddress":        ("address", None),
    "pgOrg":            ("parentGuardianInformation", "organisationName"),
    "pgOrgAddress":     ("address", None),
    # LIBRA-only section — nothing in either contract models an officer in case. Its ADDRESS is not
    # LIBRA-only, though: it shares the contract's address definition (MERGED_CONTAINERS), so it
    # resolves there and its fields take the contract's definitions like any other.
    "officerInCase":    None,
    "officerAddress":   ("address", None),
}

# The sheet names a field differently from the live contract. Keyed by (container key, the name
# LIBRA_FIELDS produces) -> the contract's own property name. Every target is checked against the
# live XHIBIT schema at generation time, so a stale entry fails the run rather than quietly
# emitting a name no contract has.
#
# These three are the same concept under another name AND another type: LIBRA supplies a
# reference-data code where the contract expects an already-resolved UUID. The contract's name and
# type are emitted; that LIBRA sends a code is recorded in the description, because nothing in the
# pipeline performs code -> UUID resolution (libra-workbook-corrections.md D4).
CONTRACT_ALIASES = {
    ("plea", "pleaCode"): "id",
    ("verdict", "verdictCode"): "id",
    ("allocation", "allocationDecisionCode"): "motReasonId",
}


# --------------------------------------------------------------------------------------
# LIBRA tab: ("<normalised section>/<normalised field>") -> (container key, property name)
# None means "not emitted as a scalar property" (handled structurally, see ATTACHMENT_ROWS).
# --------------------------------------------------------------------------------------

LIBRA_FIELDS = {
    # --- Case -------------------------------------------------------------------------
    "case/prosecutingauthority":                ("prosecutor", "prosecutingAuthority"),
    "case/originatingorganisation":             ("caseDetails", "originatingOrganisation"),
    "case/initiationcode":                      ("caseDetails", "initiationCode"),
    "case/prosecutorcasereference":             ("caseDetails", "prosecutorCaseReference"),
    "case/migrationsourcesystemcaseidentifier": ("migrationSource", "migrationSourceSystemCaseIdentifier"),
    "case/migrationsourcesystemname":           ("migrationSource", "migrationSourceSystemName"),
    "case/cpsorganisation":                     ("caseDetails", "cpsOrganisation"),
    "case/writtenchargepostingdate":            ("caseDetails", "writtenChargePostingDate"),
    "case/informant":                           ("caseDetails", "informant"),
    "case/summonscode":                         ("caseDetails", "summonsCode"),
    # --- Case Marker ------------------------------------------------------------------
    "casemarker/casemarker":                    ("caseMarkers", "markerTypeCode"),
    # --- Future Hearing(s) ------------------------------------------------------------
    "hearing/courthearinglocation":             ("hearing", "courtHearingLocation"),
    "hearing/courtroomid":                      ("hearing", "courtRoomId"),
    "hearing/dateofhearing":                    ("hearing", "dateOfHearing"),
    "hearing/timeofhearing":                    ("hearing", "timeOfHearing"),
    "hearing/durationminutes":                  ("hearing", "durationMinutes"),
    "hearing/hearingtype":                      ("hearing", "hearingType"),
    # --- Listed Defendants / Listed Offences ------------------------------------------
    "listeddefendants/prosecutordefendantid":   ("listedDefendant", "prosecutorDefendantId"),
    "listedoffences/offenceid":                 ("listedDefendant", "listedOffences"),
    # --- Officer in case (LIBRA-only section) -----------------------------------------
    "officerincase/forename":                   ("officerInCase", "forename"),
    "officerincase/forename2":                  ("officerInCase", "forename2"),
    "officerincase/forename3":                  ("officerInCase", "forename3"),
    "officerincase/surname":                    ("officerInCase", "surname"),
    "officerincase/policeofficerrank":          ("officerInCase", "policeOfficerRank"),
    "officerincase/policeworkerreferencenumber": ("officerInCase", "policeWorkerReferenceNumber"),
    "officerincase/policeworkerlocationcode":   ("officerInCase", "policeWorkerLocationCode"),
    "officerincase/uniquepropertyreferencenumber": ("officerInCase", "uniquePropertyReferenceNumber"),
    "officerincase/address1":                   ("officerAddress", "address1"),
    "officerincase/address2":                   ("officerAddress", "address2"),
    "officerincase/address3":                   ("officerAddress", "address3"),
    "officerincase/address4":                   ("officerAddress", "address4"),
    "officerincase/address5":                   ("officerAddress", "address5"),
    "officerincase/postcode":                   ("officerAddress", "postcode"),
    "officerincase/worktelephonenumber":        ("officerInCase", "workTelephoneNumber"),
    "officerincase/mobiletelephonenumber":      ("officerInCase", "mobileTelephoneNumber"),
    "officerincase/primaryemail":               ("officerInCase", "primaryEmail"),
    "officerincase/secondaryemail":             ("officerInCase", "secondaryEmail"),
    # The revised sheet calls the officer's two addresses emailAddress1/2. Nothing in either
    # contract models an officer, so the generator keeps the primary/secondary naming the rest of
    # the analysis and PCFDLRM's contact-details schema use.
    "officerincase/emailaddress1":              ("officerInCase", "primaryEmail"),
    "officerincase/emailaddress2":              ("officerInCase", "secondaryEmail"),
    "officerincase/faxnumber":                  ("officerInCase", "faxNumber"),
    "officerincase/dxaddress":                  ("officerInCase", "dxAddress"),
    # --- Defendant --------------------------------------------------------------------
    "defendant/prosecutordefendantid":          ("defendant", "prosecutorDefendantId"),
    "defendant/asn":                            ("defendant", "asn"),
    "defendant/pncidentifier":                  ("defendant", "pncIdentifier"),
    "defendant/cronumber":                      ("defendant", "croNumber"),
    "defendant/title":                          ("personalInfo", "title"),
    "defendant/forename":                       ("personalInfo", "forename"),
    "defendant/forename2":                      ("personalInfo", "middleName"),
    "defendant/forename3":                      ("personalInfo", "middleName2"),
    "defendant/surname":                        ("personalInfo", "surname"),
    "defendant/organisationname":               ("defendant", "organisationName"),
    "defendant/companytelephonenumber":         ("defendant", "telephoneNumberBusiness"),
    "defendant/organisationtelephonenumber":    ("defendant", "organisationTelephoneNumber"),
    "defendant/nationality":                    ("selfDefinedInfo", "nationality"),
    "defendant/additionalnationality":          ("selfDefinedInfo", "additionalNationality"),
    "defendant/worktelephonenumber":            ("contactDetails", "work"),
    "defendant/hometelephonenumber":            ("contactDetails", "home"),
    "defendant/mobiletelephonenumber":          ("contactDetails", "mobile"),
    "defendant/primaryemail":                   ("contactDetails", "primaryEmail"),
    "defendant/secondaryemail":                 ("contactDetails", "secondaryEmail"),
    # The revised sheet renamed these; the contract holds both on the defendant itself, not under
    # personalInformation.contactDetails, so they map there.
    "defendant/emailaddress1":                  ("defendant", "emailAddress1"),
    "defendant/emailaddress2":                  ("defendant", "emailAddress2"),
    "defendant/dateofbirth":                    ("selfDefinedInfo", "dateOfBirth"),
    "defendant/gender":                         ("selfDefinedInfo", "gender"),
    "defendant/observedethnicity":              ("personalInfo", "observedEthnicity"),
    "defendant/selfdefinedethnicity":           ("selfDefinedInfo", "ethnicity"),
    "defendant/occupation":                     ("defendant", "occupation"),
    "defendant/defendantoccupationcode":        ("defendant", "defendantOccupationCode"),
    "defendant/drivernumber":                   ("defendant", "driverNumber"),
    "defendant/licensecode":                    ("defendant", "licenseCode"),
    "defendant/documentationlanguage":          ("defendant", "documentationLanguage"),
    "defendant/hearinglanguage":                ("defendant", "hearingLanguage"),
    "defendant/languagerequirement":            ("defendant", "languageRequirement"),
    "defendant/specificrequirements":           ("defendant", "specificRequirements"),
    "defendant/custodystatus":                  ("individual", "custodyStatus"),
    "defendant/bailconditions":                 ("individual", "bailConditions"),
    "defendant/address1":                       ("address", "address1"),
    "defendant/address2":                       ("address", "address2"),
    "defendant/address3":                       ("address", "address3"),
    "defendant/address4":                       ("address", "address4"),
    "defendant/address5":                       ("address", "address5"),
    "defendant/postcode":                       ("address", "postcode"),
    "defendant/numpreviousconvictions":         ("defendant", "numPreviousConvictions"),
    "defendant/nationalinsurancenumber":        ("defendant", "nationalInsuranceNumber"),
    "defendant/prosecutorcosts":                ("defendant", "prosecutorCosts"),
    "defendant/individualaliases":              None,
    "defendant/aliasforcorporate":              ("defendant", "aliasForCorporate"),
    # --- Alias Array ------------------------------------------------------------------
    "aliasarray/alias-forename":                ("individualAlias", "firstName"),
    "aliasarray/alias-forename2":               ("individualAlias", "givenName2"),
    "aliasarray/alias-forename3":               ("individualAlias", "givenName3"),
    "aliasarray/alias-surname":                 ("individualAlias", "lastName"),
    # --- Parent Guardian --------------------------------------------------------------
    "parentguardian/parentguardian-organisationname":       ("pgOrg", "organisationName"),
    "parentguardian/parentguardian-companttelephonenumber": ("pgOrg", "companyTelephoneNumber"),
    "parentguardian/parentguardian-forename":               ("pgPersonalInfo", "forename"),
    "parentguardian/parentguardian-forename2":              ("pgPersonalInfo", "middleName"),
    "parentguardian/parentguardian-forename3":              ("pgPersonalInfo", "middleName2"),
    "parentguardian/parentguardian-surname":                ("pgPersonalInfo", "surname"),
    "parentguardian/parentguardian-worktelephonenumber":    ("pgContactDetails", "work"),
    "parentguardian/parentguardian-hometelephonenumber":    ("pgContactDetails", "home"),
    "parentguardian/parentguardian-mobiletelephonenumber":  ("pgContactDetails", "mobile"),
    "parentguardian/parentguardian-primaryemail":           ("pgContactDetails", "primaryEmail"),
    "parentguardian/parentguardian-secondaryemail":         ("pgContactDetails", "secondaryEmail"),
    "parentguardian/parentguardian-dateofbirth":            ("pgPerson", "dateOfBirth"),
    "parentguardian/parentguardian-gender":                 ("pgPerson", "gender"),
    "parentguardian/parentguardian-observedethnicity":      ("pgPersonalInfo", "observedEthnicity"),
    "parentguardian/parentguardian-selfdefinedethnicity":   ("pgPerson", "selfDefinedEthnicity"),
    "parentguardian/parentguardian-address1":               ("pgAddress", "address1"),
    "parentguardian/parentguardian-address2":               ("pgAddress", "address2"),
    "parentguardian/parentguardian-address3":               ("pgAddress", "address3"),
    "parentguardian/parentguardian-address4":               ("pgAddress", "address4"),
    "parentguardian/parentguardian-address5":               ("pgAddress", "address5"),
    "parentguardian/parentguardian-postcode":               ("pgAddress", "postcode"),
    # --- Offence ----------------------------------------------------------------------
    "offence/prosecutoroffenceid":              ("offence", "prosecutorOffenceId"),
    # The sheet renamed cjsOffenceCode to offenceCode, matching the contract; both keys are kept so
    # an earlier revision of the workbook still generates.
    "offence/cjsoffencecode":                   ("offence", "offenceCode"),
    "offence/offencecode":                      ("offence", "offenceCode"),
    "offence/offencesequenceno":                ("offence", "offenceSequenceNumber"),
    "offence/chargedate":                       ("offence", "chargeDate"),
    "offence/offencedatecode":                  ("offence", "offenceDateCode"),
    "offence/offencecommitteddate":             ("offence", "offenceCommittedDate"),
    "offence/offencecommittedenddate":          ("offence", "offenceCommittedEndDate"),
    "offence/arrestdate":                       ("offence", "arrestDate"),
    "offence/alcoholordruglevelmethod":         ("alcohol", "alcoholOrDrugLevelMethod"),
    "offence/alcoholordruglevelamount":         ("alcohol", "alcoholOrDrugLevelAmount"),
    "offence/offencelocation":                  ("offence", "offenceLocation"),
    "offence/offencewording":                   ("offence", "offenceWording"),
    "offence/offencewordingwelsh":              ("offence", "offenceWordingWelsh"),
    "offence/statementoffacts":                 ("offence", "statementOfFacts"),
    "offence/statementoffactswelsh":            ("offence", "statementOfFactsWelsh"),
    "offence/prosecutorcompensation":           ("offence", "prosecutorCompensation"),
    "offence/backduty":                         ("offence", "backDuty"),
    "offence/backdutydatefrom":                 ("offence", "backDutyDateFrom"),
    "offence/backdutydateto":                   ("offence", "backDutyDateTo"),
    "offence/vehiclecode":                      ("offence", "vehicleCode"),
    "offence/vehiclemake":                      ("offence", "vehicleMake"),
    "offence/vehicleregistrationmark":          ("offence", "vehicleRegistrationMark"),
    "offence/prosecutorofferaocp":              ("offence", "prosecutorOfferAOCP"),
    "offence/pleacode":                         ("plea", "pleaCode"),
    "offence/pleadate":                         ("plea", "pleaDate"),
    "offence/verdictcode":                      ("verdict", "verdictCode"),
    "offence/verdicttype":                      ("verdict", "verdictCode"),
    "offence/verdictdate":                      ("verdict", "verdictDate"),
    "offence/convictiondate":                   ("offence", "convictionDate"),
    "offence/allocationdecision":               ("allocation", "allocationDecisionCode"),
    "offence/allocationdecisionrecordeddate":   ("allocation", "allocationDecisionDate"),
    "offence/allocationdecisiondate":           ("allocation", "allocationDecisionDate"),
}

LIBRA_SECTIONS = [
    ("otherparty", "officerincase"),
    ("casemarker", "casemarker"),
    ("futurehearing", "hearing"),
    ("listeddefendants", "listeddefendants"),
    ("listedoffences", "listedoffences"),
    # The revised sheet renamed the heading to "Individual Alias Array - TO Change per v12 - ...".
    ("individualalias", "aliasarray"),
    ("aliasarray", "aliasarray"),
    ("parentguardian", "parentguardian"),
    ("offencerelatedfields", "offence"),
    ("defendant", "defendant"),
    ("case", "case"),
]


# Sheet rows that declare a nested container rather than a scalar field: the row's own
# mandatoriness governs whether the container is required, and it OVERRIDES the derivation in
# assemble(). Without this, `M` marks inside the Alias Array section (which mean "mandatory
# within an alias, if one is supplied") would wrongly make individualAliases itself mandatory,
# contradicting the row's own O/O/O/O.
ATTACHMENT_ROWS = {"defendant/individualaliases": "individualAlias"}


class Profile:
    """The sheet's column layout and field mapping."""

    def __init__(self, name, sheet, field, desc, fmt, rules, comment, marks, refdata,
                 fields, sections, out, schema_id, mark_fallback=None):
        self.name, self.sheet = name, sheet
        self.field, self.desc, self.fmt, self.rules, self.comment = field, desc, fmt, rules, comment
        self.marks = marks                    # OrderedDict {column index: case-type label}
        self.refdata = refdata                # (source column, format column)
        self.mark_fallback = mark_fallback    # column holding a lone mark on sub-section rows
        self.fields, self.sections = fields, sections
        self.out, self.schema_id = out, schema_id


PROFILE = Profile(
    name="LIBRA", sheet="Libra Case - Min Data",
    field=0, desc=1, fmt=2, rules=3, comment=4,
    marks=OrderedDict([(5, "SJP Referral"), (6, "Summons"), (7, "Charge"), (8, "Postal Requisition")]),
    refdata=(9, 10), mark_fallback=9,
    fields=LIBRA_FIELDS, sections=LIBRA_SECTIONS,
    out="dlrm-libra-0.13.json",
    schema_id="http://moj.gov.uk/cps/stagingdlrm/command/api/receive-migrated-case-submission-libra.json",
)


# --------------------------------------------------------------------------------------
# xlsx reading
# --------------------------------------------------------------------------------------

def _col_index(cell_ref):
    letters = "".join(c for c in cell_ref if c.isalpha())
    n = 0
    for c in letters:
        n = n * 26 + (ord(c.upper()) - 64)
    return n - 1


def read_sheet(workbook_path, sheet_name):
    """Return [(row_number, {col_index: text})] for the named sheet."""
    with zipfile.ZipFile(workbook_path) as z:
        shared = []
        if "xl/sharedStrings.xml" in z.namelist():
            for si in ET.fromstring(z.read("xl/sharedStrings.xml")).iter(NS + "si"):
                shared.append("".join(t.text or "" for t in si.iter(NS + "t")))

        book = ET.fromstring(z.read("xl/workbook.xml"))
        rels = {r.get("Id"): r.get("Target")
                for r in ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))}
        target, available = None, []
        for sheet in book.find(NS + "sheets"):
            available.append(sheet.get("name"))
            if sheet.get("name") == sheet_name:
                target = rels[sheet.get(REL_NS + "id")].lstrip("/")
        if target is None:
            sys.exit(f"error: sheet {sheet_name!r} not found. Tabs: {available}")
        if not target.startswith("xl/"):
            target = "xl/" + target

        rows = []
        for row in ET.fromstring(z.read(target)).iter(NS + "row"):
            cells = {}
            for c in row.iter(NS + "c"):
                if c.get("t") == "inlineStr":
                    text = "".join(t.text or "" for t in c.iter(NS + "t"))
                else:
                    v = c.find(NS + "v")
                    if v is None or v.text is None:
                        continue
                    text = shared[int(v.text)] if c.get("t") == "s" else v.text
                text = " ".join(text.split())
                if text:
                    cells[_col_index(c.get("r"))] = text
            rows.append((int(row.get("r")), cells))
        return rows


def norm(text):
    """Normalise a sheet label for lookup: lowercase, no spaces, dashes unified."""
    text = text.replace("–", "-").replace("—", "-").replace("’", "'")
    return re.sub(r"\s+", "", text).lower()


# --------------------------------------------------------------------------------------
# Format code -> JSON Schema fragment
# --------------------------------------------------------------------------------------

def by_property(prop, postcode):
    """The fragment this generator uses for a property by convention, or None.

    These never come from the sheet's Format cell — the shared primitives and the two array
    shapes are fixed by the contract's own vocabulary — so they are also the fragments whose
    disagreement with the contract is not a workbook signal (CONVENTION_PROPERTIES).
    """
    if prop == "postcode":
        return dict(postcode)
    if prop in ("primaryEmail", "secondaryEmail", "emailAddress1", "emailAddress2"):
        return {"$ref": "#/definitions/email"}
    if prop in ("work", "home", "mobile", "workTelephoneNumber", "mobileTelephoneNumber",
                "homeTelephoneNumber", "telephoneNumberBusiness", "companyTelephoneNumber",
                "organisationTelephoneNumber", "faxNumber"):
        return {"$ref": "#/definitions/phone"}
    if prop == "migrationSourceSystemName":
        return {"$ref": "#/definitions/migrationSourceSystemName"}
    if prop == "listedOffences":
        return {"type": "array", "minItems": 1, "items": {"type": "string", "maxLength": 36}}
    if prop == "aliasForCorporate":
        return {"type": "array", "items": {"type": "string"}}
    if prop == "startDate":
        return {"$ref": "#/definitions/datePattern"}
    return None


def fragment_for(fmt, prop, notes, postcode):
    """Translate a sheet Format code into a JSON Schema fragment.

    A<n>=text(n)  N<n>=integer(n digits)  D10=ISO date  T8=hh:mm:ss  S1=single-char code
    (+)N<n>.<d>=non-negative decimal  Boolean  Axx/TBC/blank=unknown length
    """
    fmt = (fmt or "").strip()

    convention = by_property(prop, postcode)
    if convention is not None:
        return convention

    if fmt.upper().startswith("D"):
        return {"$ref": "#/definitions/date"}
    if fmt.upper() == "T8":
        return {"type": "string", "minLength": 8, "maxLength": 8, "pattern": TIME_PATTERN}
    if fmt.lower() == "boolean":
        return {"type": "boolean"}

    money = re.fullmatch(r"\(\+\)N(\d+)\.(\d+)", fmt)
    if money:
        notes.append(f"CJS money format {fmt} ({money.group(1)} digits, {money.group(2)} dp)")
        return {"type": "number", "minimum": 0}

    numeric = re.fullmatch(r"N(\d+)", fmt, re.IGNORECASE)
    if numeric:
        return {"type": "integer", "minimum": 0, "maximum": int("9" * int(numeric.group(1)))}

    alpha = re.fullmatch(r"[AS](\d+)", fmt, re.IGNORECASE)
    if alpha:
        length = int(alpha.group(1))
        out = {"type": "string", "maxLength": length}
        if prop in OUCODE_PROPERTIES and length == 7:
            out["minLength"] = 7
        return out

    if fmt.lower() in ("axx", "tbc", ""):
        notes.append(f"Format {fmt or '(blank)'} in source sheet — length/type unconfirmed")
        return {"type": "string"}

    notes.append(f"Unrecognised source format {fmt!r} — emitted as string")
    return {"type": "string"}


def coded_values(text):
    """Pull an explicit value list out of the Business Rules / Comment text, if present."""
    values = re.findall(r"\b([A-Z])\s*=\s*[A-Za-z]", text)
    if not values:
        m = re.search(r"[Vv]alid values are\s+((?:[A-Z](?:,\s*|\s+or\s+|\s+))+[A-Z])", text)
        if m:
            values = re.findall(r"\b[A-Z]\b", m.group(1))
    return sorted(set(values))


# --------------------------------------------------------------------------------------
# The live contracts: names and definitions the workbook does not get to override
# --------------------------------------------------------------------------------------

COMPARE_KEYS = ("type", "maxLength", "minLength", "maximum", "minimum", "pattern", "enum",
                "minItems", "format")


def live_properties(schema, locator):
    """{property: fragment} for a container in a live contract. {} if it has no counterpart."""
    if locator is None:
        return {}
    name, discriminator = locator
    entry = schema.get("definitions", {}).get(name, {})
    if discriminator is None:
        return entry.get("properties") or {}
    for branch in entry.get("oneOf", []) or entry.get("anyOf", []) or []:
        properties = branch.get("properties") or {}
        if discriminator in properties:
            return properties
    return {}


def flatten_constraints(fragment, definitions):
    """A fragment's effective constraints, following one level of local $ref.

    So `{$ref: ukGovPostCode}` and the same pattern written inline compare equal — the contract
    and the sheet often express one constraint two ways, and only a real difference is a signal.
    """
    node = fragment
    ref = fragment.get("$ref", "")
    if ref.startswith("#/definitions/"):
        target = definitions.get(ref.split("/")[-1], {})
        node = {**target, **{k: v for k, v in fragment.items() if k != "$ref"}}
    out = {k: node[k] for k in COMPARE_KEYS if k in node}
    if isinstance(node.get("items"), dict):
        out["items"] = flatten_constraints(node["items"], definitions)
    return out


def summarise_fragment(fragment):
    """A one-line human reading of a fragment, for the report and the description."""
    if "$ref" in fragment:
        return f"$ref {fragment['$ref'].split('/')[-1]}"
    bits = [str(fragment.get("type", "?"))]
    for key in ("maxLength", "minLength", "maximum", "minimum"):
        if key in fragment:
            bits.append(f"{key} {fragment[key]}")
    if "pattern" in fragment:
        bits.append("pattern")
    if "enum" in fragment:
        bits.append("enum " + "/".join(map(str, fragment["enum"])))
    return " ".join(bits)


# How a sheet reading and a contract definition disagree. Only CONFLICT (and, weakly, UNSTATED)
# is a question for the workbook owner; CONTRACT_LOOSER says the gate enforces less than LIBRA's
# own data dictionary does, which is a note about the contract, not about the sheet.
OVERRIDE_UNSTATED = "sheet unstated"
OVERRIDE_CONFLICT = "conflict"
OVERRIDE_LOOSER = "contract looser"

OVERRIDE_HEADINGS = {
    OVERRIDE_UNSTATED: ("SHEET FORMAT UNSTATED, CONTRACT USED",
                        "the Format cell is blank/TBC/Axx, so the contract's definition fills the "
                        "gap. Confirm the real LIBRA constraint"),
    OVERRIDE_CONFLICT: ("SHEET AND CONTRACT CONFLICT",
                        "the sheet states a constraint the contract contradicts — a different "
                        "type, an enum the sheet does not mention, or a different bound. The "
                        "contract wins; each of these needs adjudicating"),
    OVERRIDE_LOOSER: ("CONTRACT LOOSER THAN THE SHEET",
                      "same type, but the contract does not carry a bound the sheet states, so "
                      "the gate will accept values LIBRA says cannot occur. Informational"),
}


def override_kind(fmt, sheet_constraints, contract_constraints):
    """Classify a sheet-vs-contract disagreement. See OVERRIDE_HEADINGS."""
    if (fmt or "").strip().lower() in ("", "axx", "tbc"):
        return OVERRIDE_UNSTATED
    if sheet_constraints.get("type") != contract_constraints.get("type"):
        return OVERRIDE_CONFLICT
    if any(key in contract_constraints and key not in sheet_constraints
           for key in ("enum", "pattern")):
        return OVERRIDE_CONFLICT
    if any(sheet_constraints[key] != contract_constraints[key]
           for key in set(sheet_constraints) & set(contract_constraints)):
        return OVERRIDE_CONFLICT
    return OVERRIDE_LOOSER


def contract_index(canonical, xhibit):
    """{container key: {property: fragment}} across both live contracts, plus their drift.

    Both contracts define the same fields; where both have one, the LIVE XHIBIT fragment is the one
    copied, because this schema is written to XHIBIT's shape and conventions and XHIBIT expresses a
    few constraints differently (an inline postcode pattern where the canonical module holds a
    shared `ukGovPostCode`, an inline integer where it holds `positiveInteger`). Semantics come from
    canonical either way — the two are compared field by field and any real difference is reported
    as module-vs-production drift, which is a finding about the contracts, not about LIBRA.
    Canonical fills in anything the live schema does not carry.
    """
    canon_defs = canonical.get("definitions", {})
    xhibit_defs = xhibit.get("definitions", {})
    index, drift = {}, []

    for key, locator in LIVE_DEFINITIONS.items():
        canon_props = live_properties(canonical, locator)
        xhibit_props = live_properties(xhibit, locator)
        # Live-schema order first: the emitted schema follows it, so the two files diff side by side.
        merged = OrderedDict()
        for prop in list(xhibit_props) + [p for p in canon_props if p not in xhibit_props]:
            in_canon, in_xhibit = prop in canon_props, prop in xhibit_props
            if not in_xhibit:
                drift.append((key, prop, "in canonical only"))
            elif not in_canon:
                drift.append((key, prop, "in the live XHIBIT schema only"))
            elif (flatten_constraints(canon_props[prop], canon_defs)
                  != flatten_constraints(xhibit_props[prop], xhibit_defs)):
                drift.append((key, prop,
                              f"canonical {summarise_fragment(canon_props[prop])} vs live XHIBIT "
                              f"{summarise_fragment(xhibit_props[prop])} — XHIBIT's is emitted"))
            merged[prop] = xhibit_props[prop] if in_xhibit else canon_props[prop]
        index[key] = merged

    return index, drift


def contract_shape(xhibit):
    """(definition descriptions, definition order, root property order) from the live contract.

    The shared schema takes all three, so it reads and diffs like the contract DLRM already has: the
    same wording on each definition, and the same order both down the file and within each object.
    """
    definitions = xhibit.get("definitions", {})
    descriptions = {name: entry["description"] for name, entry in definitions.items()
                    if isinstance(entry, dict) and entry.get("description")}
    root = ((xhibit.get("properties") or {}).get(REFERENCE_ROOT_PROPERTY) or {})
    return descriptions, list(definitions), list(root.get("properties") or {})


def check_aliases(index, xhibit):
    """Every alias must land on a property the live XHIBIT schema actually declares."""
    stale = []
    for (container_key, sheet_name), target in sorted(CONTRACT_ALIASES.items()):
        live = live_properties(xhibit, LIVE_DEFINITIONS.get(container_key))
        if target not in live:
            stale.append(f"{container_key}.{sheet_name} -> {target}: the live XHIBIT schema's "
                         f"{container_key} has no {target!r} (it has: "
                         f"{', '.join(sorted(live)) or 'nothing'})")
        elif container_key not in index:
            stale.append(f"{container_key}.{sheet_name} -> {target}: {container_key} is not a "
                         "known container")
    if stale:
        sys.exit("error: CONTRACT_ALIASES no longer match the live XHIBIT schema:\n  "
                 + "\n  ".join(stale))


def referenced_definitions(fragment, definitions, found):
    """Collect the local definitions a copied fragment depends on, transitively."""
    if isinstance(fragment, dict):
        ref = fragment.get("$ref", "")
        if ref.startswith("#/definitions/"):
            name = ref.split("/")[-1]
            if name not in found:
                found.add(name)
                referenced_definitions(definitions.get(name, {}), definitions, found)
        for value in fragment.values():
            referenced_definitions(value, definitions, found)
    elif isinstance(fragment, list):
        for item in fragment:
            referenced_definitions(item, definitions, found)
    return found


# --------------------------------------------------------------------------------------
# Build
# --------------------------------------------------------------------------------------

def mandatoriness(cells, profile):
    """Return {case type: mark}. Some sub-section rows carry a lone mark in another column."""
    marks = {label: cells.get(idx, "").strip() for idx, label in profile.marks.items()}
    if not any(marks.values()) and profile.mark_fallback is not None:
        fallback = cells.get(profile.mark_fallback, "").strip()
        if fallback in ("M", "O", "CM", "N/A"):
            marks = {label: fallback for label in profile.marks.values()}
    return marks


MARK_LABELS = {"M": "Mandatory", "O": "Optional", "CM": "Conditionally mandatory",
               "N/A": "Not applicable"}


def sheet_meaning(cells, profile):
    """The field's meaning as the sheet states it — the Description column and nothing else.

    This is the only workbook text that reaches the shared schema, and only for a field the live
    contract does not already describe. Mandatoriness, business rules, comments, ref-data sources
    and row numbers are provenance, not contract, and go to the sidecar.
    """
    text = (cells.get(profile.desc) or "").strip().rstrip(".")
    return f"{text}." if text else None


def provenance_for(row_no, section, cells, marks, profile, container_key, prop, required,
                   unreferenced, difference, sheet_fragment, values):
    """Everything the sheet says about a field, for the sidecar. Never for the shared schema."""
    record = OrderedDict()
    record["sheetRow"] = row_no
    record["sheetSection"] = section
    record["sheetField"] = cells.get(profile.field, "")
    record["definition"] = definition_of(container_key)
    record["property"] = prop
    if cells.get(profile.desc):
        record["description"] = cells[profile.desc]
    record["format"] = cells.get(profile.fmt, "")
    record["mandatoriness"] = OrderedDict(
        (case_type, MARK_LABELS.get(mark, mark)) for case_type, mark in marks.items() if mark)
    record["required"] = required
    if cells.get(profile.rules):
        record["businessRules"] = cells[profile.rules]
    if cells.get(profile.comment):
        record["comment"] = cells[profile.comment]
    if cells.get(profile.refdata[0]):
        record["referenceDataSource"] = cells[profile.refdata[0]]
    if values:
        record["documentedValues"] = values
    record["inContract"] = not unreferenced
    if difference is not None:
        record["sheetConstraint"] = OrderedDict([("reads", sheet_fragment),
                                                 ("difference", difference)])
    return record


def build(rows, reference, live, profile, reference_name, postcode):
    containers = {key: {"props": OrderedDict(), "required": [], "rows": {}} for key in CONTAINERS}
    report = {"unmapped": [], "unreferenced": [], "conflicts": [], "attachment_marks": {},
              "renamed": [], "inherited": [], "overridden": [], "copied_refs": set(),
              "provenance": [], "intersected": [],
              "counts": {"total": len(rows), "blank": 0, "section": 0, "header": 0,
                         "field": 0, "structural": 0}}
    reference_defs = reference.get("definitions", {})

    section = None
    for row_no, cells in rows:
        name = cells.get(profile.field, "")
        if not name:
            report["counts"]["blank"] += 1
            continue
        # A heading names a section and carries neither a description nor a format.
        if not cells.get(profile.desc) and not cells.get(profile.fmt):
            key = norm(name)
            section = next((mapped for prefix, mapped in profile.sections
                            if key.startswith(prefix)), key)
            report["counts"]["section"] += 1
            continue
        if norm(name) == "fieldname":
            report["counts"]["header"] += 1
            continue
        if section is None:
            report["counts"]["header"] += 1
            continue
        report["counts"]["field"] += 1

        lookup = f"{section}/{norm(name)}"
        if lookup not in profile.fields:
            report["unmapped"].append((row_no, section, name))
            continue
        mapping = profile.fields[lookup]
        if mapping is None:
            report["counts"]["structural"] += 1
            if lookup in ATTACHMENT_ROWS:
                marks = mandatoriness(cells, profile)
                container_key = ATTACHMENT_ROWS[lookup]
                parent, prop = CONTAINERS[container_key][1], CONTAINERS[container_key][2]
                required = all(m == "M" for m in marks.values() if m)
                report["attachment_marks"][container_key] = required
                record = provenance_for(row_no, section, cells, marks, profile, parent, prop,
                                        required, False, None, None, None)
                # The row declares a nested array, not a field: it has no constraints of its own,
                # and its mandatoriness is carried by the attachment.
                record["attachment"] = True
                report["provenance"].append((parent, prop, record))
            continue

        container_key, sheet_prop = mapping
        # The contract's name wins over the sheet's. check_aliases() has already verified that
        # every target exists in the live XHIBIT schema.
        prop = CONTRACT_ALIASES.get((container_key, sheet_prop), sheet_prop)
        if norm(name) != norm(prop):
            report["renamed"].append((row_no, container_key, name, prop,
                                      "CONTRACT_ALIASES" if prop != sheet_prop else "field map"))

        fmt = cells.get(profile.fmt, "")
        sheet_notes = []
        sheet_fragment = fragment_for(fmt, prop, sheet_notes, postcode)

        # A field the contract already declares takes the contract's definition, not the sheet's.
        inherited = live.get(container_key, {}).get(prop)
        difference = None            # how the sheet's own reading disagrees, if it does
        if inherited is None:
            fragment, notes = sheet_fragment, sheet_notes
        else:
            fragment = json.loads(json.dumps(inherited))     # copied, never referenced in place
            fragment.pop("description", None)
            report["inherited"].append((container_key, prop))
            notes = []
            # Only a Format-cell reading can disagree with the contract in a way the workbook owner
            # can act on; a CONVENTION_PROPERTIES fragment is this generator's own choice.
            sheet_constraints = flatten_constraints(sheet_fragment, reference_defs)
            contract_constraints = flatten_constraints(fragment, reference_defs)
            if prop not in CONVENTION_PROPERTIES and sheet_constraints != contract_constraints:
                difference = override_kind(fmt, sheet_constraints, contract_constraints)
                sheet_reading = summarise_fragment(sheet_fragment)
                contract_reading = summarise_fragment(fragment)
                notes.append(f"Sheet format {fmt or '(blank)'} reads as {sheet_reading}; the "
                             f"contract's {contract_reading} is used instead ({difference})")
                report["overridden"].append((difference, row_no, container_key, prop,
                                             fmt or "(blank)", sheet_reading, contract_reading))

        values = coded_values(f"{cells.get(profile.rules, '')} {cells.get(profile.comment, '')}")
        if values and len(values) > 1 and EMIT_CODE_ENUMS and "enum" not in fragment \
                and fragment.get("type") == "string":
            fragment["enum"] = values

        # Whatever the fragment ended up being, the definitions it points at have to travel with it.
        referenced_definitions(fragment, reference_defs, report["copied_refs"])

        unreferenced = inherited is None
        # The shared schema carries the contract's own wording where it has one, the sheet's
        # Description column where it does not, and nothing else. Everything the sheet says about
        # the field — its name there, its Format cell, mandatoriness per case type, business rules,
        # comments, row number — is provenance and goes to the sidecar.
        if unreferenced:
            meaning = sheet_meaning(cells, profile)
            if meaning:
                fragment["description"] = meaning
        elif "description" in fragment and not fragment["description"].strip():
            fragment.pop("description")

        marks = mandatoriness(cells, profile)
        # Required only if the row is marked, and every mark it does carry is `M`. A BLANK cell
        # means "not stated for this case type", not "optional" — the sheet leaves whole column
        # groups blank per section, so treating blank as non-mandatory would drop almost every
        # required field. Any O/CM/N/A anywhere disqualifies: a single shared schema cannot
        # demand a field that some case type says is optional or inapplicable.
        stated = [m for m in marks.values() if m]
        required = bool(stated) and all(m == "M" for m in stated)

        report["provenance"].append((container_key, prop, provenance_for(
            row_no, section, cells, marks, profile, container_key, prop, required, unreferenced,
            difference, sheet_fragment, values if len(values) > 1 else None)))
        if unreferenced:
            report["unreferenced"].append((row_no, section, name, f"{container_key}.{prop}"))

        bucket = containers[container_key]
        if prop in bucket["props"]:
            report["conflicts"].append((row_no, container_key, prop, bucket["rows"][prop]))
            continue
        bucket["props"][prop] = fragment
        bucket["rows"][prop] = row_no
        if required:
            bucket["required"].append(prop)

    return containers, report


def reference_postcode(schema):
    """The fragment to use for a postcode the contract does not model (the officer's address).

    Reuses the reference's own `address.postcode` — a `$ref` to the shared `ukGovPostCode`
    definition where it has one, so the regex lives in exactly one place, and the inline pattern
    otherwise. Returns None if the reference has no postcode at all.
    """
    definitions = schema.get("definitions", {})
    node = definitions.get("address", {}).get("properties", {}).get("postcode", {})
    ref = node.get("$ref", "")
    if ref.startswith("#/definitions/") and "pattern" in definitions.get(ref.split("/")[-1], {}):
        return {"$ref": ref}
    return ({"type": "string", "pattern": node["pattern"], "maxLength": 8}
            if "pattern" in node else None)


def is_structural(fragment, definitions):
    """True if a contract property is a nested object/array, not a leaf the sheet would supply."""
    node = fragment
    ref = fragment.get("$ref", "")
    if ref.startswith("#/definitions/"):
        node = definitions.get(ref.split("/")[-1], {})
    if node.get("properties") or node.get("oneOf") or node.get("anyOf"):
        return True
    items = node.get("items")
    return isinstance(items, dict) and is_structural(items, definitions)


def ordered(props, reference_order):
    """`props` in the live contract's own order, with anything it does not have kept last."""
    rank = {name: i for i, name in enumerate(reference_order)}
    return OrderedDict(sorted(props.items(), key=lambda kv: rank.get(kv[0], len(rank))))


def definition_of(key):
    """The definition a container's fields end up in — its own, or the one it shares."""
    if key in PG_BRANCHES:
        return PG_DEFINITION
    return CONTAINERS[MERGED_CONTAINERS.get(key, key)][0]


def assemble(containers, reference, live, live_descriptions, live_order, root_order,
             workbook, profile, report):
    definitions = OrderedDict()

    # Child containers attach into their parent as $ref before parents are emitted.
    # A child whose own contents include a mandatory field makes the attachment itself
    # mandatory in the parent (this is how the canonical schema ends up requiring
    # caseDetails.prosecutor and defendant.offences). pgPerson is excluded: it attaches through a
    # oneOf, and `M` marks there mean "mandatory within the block if a guardian exists".
    derived_required, empty_attachments = [], []
    for key, (def_name, parent, prop, is_array, _strict) in CONTAINERS.items():
        if parent is None or prop is None:
            continue
        if not containers[key]["props"]:
            if key in report["attachment_marks"]:
                empty_attachments.append((prop, def_name))
            continue

        explicit = report["attachment_marks"].get(key)
        is_required = bool(containers[key]["required"]) and key != "pgPerson"
        if explicit is not None:
            is_required = explicit           # the sheet's own row for this container wins

        # A merged container attaches as a $ref to the definition it shares, not to one of its own.
        ref = {"$ref": f"#/definitions/{definition_of(key)}"}
        attach = {"type": "array", "items": ref} if is_array else ref
        if is_array and is_required:
            attach["minItems"] = 1
        containers[parent]["props"][prop] = attach
        if is_required:
            containers[parent]["required"].append(prop)
            if explicit is None:
                derived_required.append(f"{definition_of(parent)}.{prop}")

    # parentGuardianInformation is ONE definition holding a oneOf of two inline branches, which is
    # how both live contracts model it.
    for key in PG_BRANCHES:
        if not containers[key]["props"]:
            continue
        containers["individual"]["props"].setdefault(
            PG_DEFINITION, {"$ref": f"#/definitions/{PG_DEFINITION}"})

    # One definition per distinct target, fields unioned and `required` INTERSECTED across the
    # sections that contribute to it (MERGED_CONTAINERS).
    contributors = OrderedDict()
    for key in CONTAINERS:
        if containers[key]["props"] and key not in PG_BRANCHES:   # the pg pair is emitted below
            contributors.setdefault(definition_of(key), []).append(key)

    for def_name, keys in contributors.items():
        props, required, strict = OrderedDict(), None, False
        for key in keys:
            bucket = containers[key]
            for prop, fragment in bucket["props"].items():
                props.setdefault(prop, fragment)
            own = set(bucket["required"])
            # Plain intersection: a section that does not carry the field at all is a section that
            # does not require it. Anything looser over-constrains whichever section lacks it —
            # requiring `address` on the shared personalInformation because a parent guardian must
            # have one would force one onto every defendant too.
            required = own if required is None else required & own
            strict = strict or CONTAINERS[key][4]
        if len(keys) > 1:
            for key in keys:
                for prop in sorted(set(containers[key]["required"]) - required):
                    report["intersected"].append((def_name, prop, key))
        entry = OrderedDict([("type", "object")])
        description = live_descriptions.get(def_name, LIBRA_ONLY_DESCRIPTIONS.get(def_name))
        if description:
            entry["description"] = description
        entry["properties"] = ordered(props, live.get(keys[0], {}))
        if required:
            entry["required"] = sorted(required)
        if strict:
            entry["additionalProperties"] = False
        definitions[def_name] = entry

    if all(containers[key]["props"] for key in PG_BRANCHES):
        branches = []
        for key in PG_BRANCHES:
            branch = OrderedDict([("properties", ordered(containers[key]["props"],
                                                         live.get(key, {})))])
            if containers[key]["required"]:
                branch["required"] = sorted(containers[key]["required"])
            branch["additionalProperties"] = False
            branches.append(branch)
        entry = OrderedDict([("type", "object")])
        if PG_DEFINITION in live_descriptions:
            entry["description"] = live_descriptions[PG_DEFINITION]
        entry["oneOf"] = branches
        definitions[PG_DEFINITION] = entry

    report["derived_required"] = derived_required
    report["empty_attachments"] = empty_attachments

    # Shared primitives: those the emitted fragments actually point at, and no others — an unused
    # definition is noise in a schema meant to be read by the people building the extract.
    extra = sorted(report["copied_refs"] - set(COPIED_DEFINITIONS) - set(definitions))
    for name in COPIED_DEFINITIONS + extra:
        if name not in report["copied_refs"]:
            continue
        if name in reference.get("definitions", {}):
            definitions[name] = reference["definitions"][name]
        else:
            sys.exit(f"error: an emitted fragment references #/definitions/{name}, which the "
                     "reference schema does not define")

    migrated_case = OrderedDict()
    for key, (def_name, parent, prop, is_array, _s) in CONTAINERS.items():
        if parent is not None or prop is None or not containers[key]["props"]:
            continue
        ref = {"$ref": f"#/definitions/{def_name}"}
        migrated_case[prop] = {"type": "array", "items": ref} if is_array else ref

    # Definition order follows the live contract's, so the two files diff cleanly side by side;
    # definitions the contract does not have keep their CONTAINERS order and come last.
    order = {name: i for i, name in enumerate(live_order)}
    definitions = OrderedDict(sorted(definitions.items(),
                                     key=lambda kv: order.get(kv[0], len(order))))

    migrated_case = ordered(migrated_case, root_order)

    return OrderedDict([
        ("$schema", "http://json-schema.org/draft-04/schema#"),
        ("id", profile.schema_id),
        ("type", "object"),
        ("description", f"Migrated Case File Submission ({profile.name}) - Combined Schema"),
        ("properties", {"migratedCase": OrderedDict([
            ("type", "object"),
            ("properties", migrated_case),
            # Mirrors the reference envelope; the sheets do not mark these containers.
            ("required", ["caseDetails", "defendants", "migrationSourceSystem"]),
            ("additionalProperties", False),
        ])}),
        ("definitions", definitions),
        ("required", ["migratedCase"]),
        ("additionalProperties", False),
    ])


# --------------------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------------------

def print_report(report, containers, reference, live, drift, out_path, wrote, profile,
                 reference_name):
    reference_defs = reference.get("definitions", {})
    emitted = {p for c in containers.values() for p in c["props"]}
    counts = report["counts"]
    mapped = counts["field"] - len(report["unmapped"]) - counts["structural"] - len(report["conflicts"])

    print(f"{'WROTE' if wrote else 'DRY RUN'} [{profile.name}]: {out_path}")
    print(f"  case-type columns: {', '.join(profile.marks.values())}")
    print(f"  sheet rows: {counts['total']} = {counts['blank']} blank + {counts['section']} section"
          f" + {counts['header']} header + {counts['field']} field")
    print(f"  field rows: {mapped} emitted + {counts['structural']} structural"
          f" + {len(report['unmapped'])} unmapped + {len(report['conflicts'])} conflicting")
    print(f"  definitions: {sum(1 for c in containers.values() if c['props'])}"
          f"   distinct property names: {len(emitted)}")
    print(f"  contract-defined fields: {len(report['inherited'])} inherited"
          f" ({len(report['overridden'])} overriding the sheet's own format)"
          f" + {len(report['unreferenced'])} LIBRA-only")

    if report["intersected"]:
        print(f"\n  REQUIRED INTERSECTED AWAY ({len(report['intersected'])}) — the contract reuses"
              " one definition where the")
        print("  sheet marks the field differently per section, so it cannot be mandatory in the"
              " schema. Enforce")
        print("  these in the LIBRA validation rules:")
        for def_name, prop, key in report["intersected"]:
            print(f"    {def_name}.{prop}: mandatory at {container_path(key)},"
                  f" not everywhere {def_name} is used")

    if report["renamed"]:
        print(f"\n  NAME MAPPED TO THE CONTRACT ({len(report['renamed'])}) — the sheet's own field"
              " name is not the")
        print("  contract's; the contract's name is emitted, the sheet's is recorded in the"
              " provenance sidecar:")
        for row_no, container_key, sheet_name, prop, source in report["renamed"]:
            print(f"    row {row_no:>3}  {sheet_name} -> {CONTAINERS[container_key][0]}.{prop}"
                  f"  [{source}]")

    for kind in (OVERRIDE_CONFLICT, OVERRIDE_UNSTATED, OVERRIDE_LOOSER):
        group = [o for o in report["overridden"] if o[0] == kind]
        if not group:
            continue
        heading, explanation = OVERRIDE_HEADINGS[kind]
        print(f"\n  {heading} ({len(group)}) — {explanation}:")
        for _kind, row_no, container_key, prop, fmt, sheet_reading, contract in group:
            print(f"    row {row_no:>3}  {CONTAINERS[container_key][0]}.{prop}")
            print(f"             sheet {fmt}: {sheet_reading}")
            print(f"             contract:   {contract}")

    if drift:
        print(f"\n  LIVE CONTRACT DRIFT ({len(drift)}) — the canonical module and the live XHIBIT"
              " schema disagree.")
        print("  Not a LIBRA finding; the canonical definition was used where both have the"
              " field:")
        for container_key, prop, detail in drift:
            print(f"    {CONTAINERS[container_key][0]}.{prop}: {detail}")

    if report["unmapped"]:
        print(f"\n  UNMAPPED sheet rows ({len(report['unmapped'])}) — not in the profile's field map:")
        for row_no, section, name in report["unmapped"]:
            print(f"    row {row_no:>3}  [{section}] {name}")

    if report["conflicts"]:
        print(f"\n  CONFLICTS ({len(report['conflicts'])}) — property claimed twice, later row dropped:")
        for row_no, container, prop, first in report["conflicts"]:
            print(f"    row {row_no:>3}  {container}.{prop} (already set from row {first})")

    if report.get("empty_attachments"):
        print(f"\n  DECLARED BUT UNDEFINED ({len(report['empty_attachments'])}) — the sheet declares"
              " the array but has no")
        print("  section defining its item shape, so it is omitted from the schema:")
        for prop, def_name in report["empty_attachments"]:
            print(f"    {prop} (would be #/definitions/{def_name})")

    if report["unreferenced"]:
        print(f"\n  NOT IN {reference_name} ({len(report['unreferenced'])}) — emitted and flagged:")
        for row_no, section, name, prop in report["unreferenced"]:
            print(f"    row {row_no:>3}  [{section}] {name} -> {prop}")

    # Reverse delta, per container: a contract field the sheet never supplies. Scoped by container
    # rather than by bare name, so `officerInCase` no longer masks `personalInformation.surname`
    # and the parent-guardian block is compared against the contract's own oneOf branches.
    missing = []
    for key, props in live.items():
        if not containers[key]["props"]:
            continue
        for prop, fragment in props.items():
            if prop not in containers[key]["props"] and not is_structural(fragment, reference_defs):
                missing.append(f"{CONTAINERS[key][0]}.{prop}")
    if missing:
        print(f"\n  IN {reference_name}, ABSENT FROM THE SHEET ({len(missing)}) — reverse delta:")
        print("    " + ", ".join(sorted(missing)))

    if report.get("derived_required"):
        print(f"\n  DERIVED required ({len(report['derived_required'])}) — nested objects the sheet")
        print("  does not mark directly, made mandatory because they contain a mandatory field:")
        print("    " + ", ".join(report["derived_required"]))


# --------------------------------------------------------------------------------------
# Comparison against a hand-written schema
# --------------------------------------------------------------------------------------

CONSTRAINT_KEYS = ["type", "$ref", "maxLength", "minLength", "maximum", "minimum", "pattern",
                   "enum", "items", "minItems", "oneOf"]


def constraints(fragment):
    out = OrderedDict()
    for key in CONSTRAINT_KEYS:
        if key in fragment:
            value = fragment[key]
            if key == "items" and isinstance(value, dict):
                value = value.get("$ref") or value.get("type")
            if key == "oneOf" and isinstance(value, list):
                value = [b.get("$ref", "<inline>") if isinstance(b, dict) else b for b in value]
            if key == "pattern":
                value = "<pattern>"
            out[key] = value
    return out


def compare(generated, other, other_path):
    """Structural diff: definitions, properties, required lists, constraints."""
    gen_defs, oth_defs = generated.get("definitions", {}), other.get("definitions", {})
    name = Path(other_path).name
    print(f"\n{'=' * 86}\nCOMPARISON: generated-from-sheet  vs  {name}\n{'=' * 86}")

    only_gen = sorted(set(gen_defs) - set(oth_defs))
    only_oth = sorted(set(oth_defs) - set(gen_defs))
    if only_gen:
        print(f"\n  Definitions only in the GENERATED schema ({len(only_gen)}):\n    "
              + ", ".join(only_gen))
    if only_oth:
        print(f"\n  Definitions only in {name} ({len(only_oth)}):\n    " + ", ".join(only_oth))

    prop_diffs, req_diffs, con_diffs = [], [], []
    for def_name in sorted(set(gen_defs) & set(oth_defs)):
        g, o = gen_defs[def_name], oth_defs[def_name]
        gp, op = g.get("properties", {}), o.get("properties", {})
        for prop in sorted(set(gp) - set(op)):
            prop_diffs.append((def_name, prop, "in the sheet, NOT in the schema"))
        for prop in sorted(set(op) - set(gp)):
            prop_diffs.append((def_name, prop, "in the schema, NOT in the sheet"))

        gr, or_ = set(g.get("required", [])), set(o.get("required", []))
        for prop in sorted(gr - or_):
            req_diffs.append((def_name, prop, "required by the sheet, optional in the schema"))
        for prop in sorted(or_ - gr):
            req_diffs.append((def_name, prop, "required by the schema, not by the sheet"))

        for prop in sorted(set(gp) & set(op)):
            gc, oc = constraints(gp[prop]), constraints(op[prop])
            if gc != oc:
                con_diffs.append((def_name, prop, dict(gc), dict(oc)))

    print(f"\n  PROPERTY differences ({len(prop_diffs)}):")
    for def_name, prop, note in prop_diffs:
        print(f"    {def_name}.{prop}: {note}")

    print(f"\n  REQUIRED differences ({len(req_diffs)}):")
    for def_name, prop, note in req_diffs:
        print(f"    {def_name}.{prop}: {note}")

    print(f"\n  CONSTRAINT differences ({len(con_diffs)}):")
    for def_name, prop, gc, oc in con_diffs:
        print(f"    {def_name}.{prop}\n      sheet:  {gc}\n      schema: {oc}")

    print(f"\n  TOTALS: {len(only_gen) + len(only_oth)} definition, {len(prop_diffs)} property, "
          f"{len(req_diffs)} required, {len(con_diffs)} constraint differences.")


# --------------------------------------------------------------------------------------
# The provenance sidecar
# --------------------------------------------------------------------------------------

def container_path(key):
    """The JSONPath prefix a container's fields sit at, e.g. $.migratedCase.defendants[*]."""
    parent, prop = PATH_PARENT.get(key, (CONTAINERS[key][1], CONTAINERS[key][2]))
    if prop is None:
        return None
    is_array = CONTAINERS[key][3]
    suffix = f"{prop}[*]" if is_array else prop
    if parent is None:
        return f"$.migratedCase.{suffix}"
    prefix = container_path(parent)
    return f"{prefix}.{suffix}" if prefix else None


def provenance_document(report, profile, workbook, reference_name):
    """Everything the workbook says, keyed by JSONPath — the internal half of the generation.

    Kept out of the schema so that file can be shared as a contract, and structured rather than
    left in prose so build-schema-impact.py can join on it exactly.
    """
    fields = OrderedDict()
    for container_key, prop, record in report["provenance"]:
        prefix = container_path(container_key)
        fields[f"{prefix}.{prop}" if prefix else prop] = record

    deviations = []
    for def_name, prop, key in report["intersected"]:
        deviations.append(f"{def_name}.{prop}: the sheet makes it mandatory at "
                          f"{container_path(key)}, but the contract reuses {def_name} elsewhere and "
                          "the sheet does not make it mandatory there, so `required` was "
                          "intersected away — enforce it in the LIBRA validation rules")
    for kind, row_no, container_key, prop, fmt, sheet_reading, contract in report["overridden"]:
        deviations.append(f"{definition_of(container_key)}.{prop}: sheet row {row_no} Format {fmt} "
                          f"reads as {sheet_reading}; the contract's {contract} was emitted "
                          f"({kind})")

    return OrderedDict([
        ("description",
         f"Provenance for {profile.out} — what the workbook says about each field, and every point "
         "at which the live contract overrode it. Generated with the schema by "
         "tools/schema-gen/generate-dlrm-schema.py; do not hand-edit. This file is INTERNAL: the "
         "schema is the shareable artefact and deliberately carries none of this."),
        ("workbook", Path(workbook).name),
        ("sheet", profile.sheet),
        ("contract", reference_name),
        ("caseTypeColumns", list(profile.marks.values())),
        ("requiredRule",
         "A field is `required` in the schema only where every case-type column that states a mark "
         "says M. A blank cell means 'not stated for this case type' and is ignored; any O/CM/N/A "
         "disqualifies. Per-case-type mandatoriness is in each field's `mandatoriness` below and "
         "must be enforced by the source-system validation rules, not by the schema."),
        ("deviations", deviations),
        ("fields", fields),
    ])


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--workbook", default=str(DEFAULT_WORKBOOK))
    ap.add_argument("--sheet", help="override the sheet name")
    ap.add_argument("--reference", default=str(DEFAULT_REFERENCE),
                    help="the schema field definitions and shared primitives are taken from, and "
                         "novel fields are flagged against (default: the flattened canonical "
                         "schema — generate it first with flatten-canonical-schema.py)")
    ap.add_argument("--xhibit", default=str(DEFAULT_XHIBIT),
                    help="the live XHIBIT contract, used to check every name mapping and to fill "
                         "in fields the canonical module does not carry "
                         f"(default: {DEFAULT_XHIBIT.name})")
    ap.add_argument("--out-dir", default=".",
                    help="directory to write the schema and its provenance sidecar into "
                         "(default: current directory)")
    ap.add_argument("--out", help="explicit output path; overrides --out-dir")
    ap.add_argument("--provenance",
                    help="explicit path for the provenance sidecar (default: the schema's name "
                         f"with '{PROVENANCE_SUFFIX}')")
    ap.add_argument("--compare", metavar="SCHEMA",
                    help="also diff the generated schema against an existing one")
    ap.add_argument("--dry-run", action="store_true", help="report only, write nothing")
    args = ap.parse_args()

    profile = PROFILE
    sheet = args.sheet or profile.sheet
    out_path = Path(args.out) if args.out else Path(args.out_dir) / profile.out

    for path in (args.workbook, args.reference, args.xhibit):
        if not Path(path).exists():
            sys.exit(f"error: not found: {path}")

    reference = json.loads(Path(args.reference).read_text(encoding="utf-8"))
    reference_name = Path(args.reference).name
    xhibit = json.loads(Path(args.xhibit).read_text(encoding="utf-8"))

    postcode = reference_postcode(reference)
    if not postcode:
        sys.exit(f"error: no address.postcode pattern in {args.reference}")

    live, drift = contract_index(reference, xhibit)
    check_aliases(live, xhibit)
    descriptions, order, root_order = contract_shape(xhibit)

    rows = read_sheet(args.workbook, sheet)
    containers, report = build(rows, reference, live, profile, reference_name, postcode)
    schema = assemble(containers, reference, live, descriptions, order, root_order,
                      args.workbook, profile, report)
    provenance = provenance_document(report, profile, args.workbook, reference_name)
    provenance_path = Path(args.provenance) if args.provenance else out_path.with_name(
        out_path.stem + PROVENANCE_SUFFIX)

    if not args.dry_run:
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(schema, indent=2, ensure_ascii=False) + "\n",
                            encoding="utf-8")
        provenance_path.parent.mkdir(parents=True, exist_ok=True)
        provenance_path.write_text(json.dumps(provenance, indent=2, ensure_ascii=False) + "\n",
                                   encoding="utf-8")

    print_report(report, containers, reference, live, drift, out_path, not args.dry_run, profile,
                 reference_name)

    if args.compare:
        if not Path(args.compare).exists():
            sys.exit(f"error: not found: {args.compare}")
        compare(schema, json.loads(Path(args.compare).read_text(encoding="utf-8")), args.compare)


if __name__ == "__main__":
    main()
