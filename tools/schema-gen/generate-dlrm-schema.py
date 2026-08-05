#!/usr/bin/env python3
"""Generate the LIBRA DLRM JSON Schema from the DLRM CP Migration Data Schema workbook.

Reads the 'Libra Case - Min Data' tab and emits a draft-04 JSON Schema shaped like the canonical
runtime schema: a `migratedCase` envelope over caseDetails / hearings / defendants[], with shared
`definitions`.

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

# The sheet documents initiation codes (LIBRA C/S/Q/J). Emitted as plain
# strings with the values in the description rather than an `enum`, because an over-tight enum
# compiles into a closed Java enum downstream and a schema rejection is terminal, not retryable
# (libra-ingestion-analysis.md 3.3, 4). Flip to True to emit enums instead.
EMIT_CODE_ENUMS = False

# Primitive definitions copied verbatim from the reference schema (never re-typed here).
COPIED_DEFINITIONS = ["date", "datePattern", "uuid", "phone", "email", "migrationSourceSystemName"]

NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
REL_NS = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"

TIME_PATTERN = r"^(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d$"

# Fields the reference schema constrains as a 7-character CJS OU code.
OUCODE_PROPERTIES = {"prosecutingAuthority", "originatingOrganisation", "cpsOrganisation",
                     "courtHearingLocation", "policeWorkerLocationCode", "sendingCourt",
                     "receivingCourt", "convictingCourtCode"}

# The reference definition the novel-field check is scoped to — see reference_property_index().
REFERENCE_ROOT_DEFINITION = "migratedCase"

# Sheet names the canonical schema carries under a different property name, so they are NOT novel.
# Mirrors RENAMES in build-schema-impact.py, which owns the canonical-side mapping.
REFERENCE_ALIASES = {"pleaCode", "verdictCode", "allocationDecisionCode"}


# --------------------------------------------------------------------------------------
# Target structure: containers become `definitions` entries in the emitted schema
#   key: (definition name, parent container key, property on parent, array?, strict?)
# `strict` mirrors whether the reference schema sets additionalProperties:false there.
# --------------------------------------------------------------------------------------

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
    # Parent guardian gets its own definitions rather than reusing the shared
    # personalInformation/address/contactDetails ones (which is what the canonical schema does),
    # because the sheet's mandatoriness for these rows differs from the defendant's — sharing would
    # silently weaken the defendant's own required list. Reported as a deviation.
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

# Containers with no counterpart anywhere in the reference schema — every field inside them is
# source-system-only, even where the property name (surname, address1, ...) coincides with one
# used elsewhere in the reference.
UNREFERENCED_CONTAINERS = {"officerInCase", "officerAddress"}


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
    "hearing/dateofhearing":                    ("hearing", "dateOfHearing"),
    "hearing/timeofhearing":                    ("hearing", "timeOfHearing"),
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
    "offence/cjsoffencecode":                   ("offence", "offenceCode"),
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
    "offence/verdictdate":                      ("verdict", "verdictDate"),
    "offence/convictiondate":                   ("offence", "convictionDate"),
    "offence/allocationdecision":               ("allocation", "allocationDecisionCode"),
    "offence/allocationdecisionrecordeddate":   ("allocation", "allocationDecisionDate"),
}

LIBRA_SECTIONS = [
    ("otherparty", "officerincase"),
    ("casemarker", "casemarker"),
    ("futurehearing", "hearing"),
    ("listeddefendants", "listeddefendants"),
    ("listedoffences", "listedoffences"),
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

def fragment_for(fmt, prop, notes, postcode_pattern):
    """Translate a sheet Format code into a JSON Schema fragment.

    A<n>=text(n)  N<n>=integer(n digits)  D10=ISO date  T8=hh:mm:ss  S1=single-char code
    (+)N<n>.<d>=non-negative decimal  Boolean  Axx/TBC/blank=unknown length
    """
    fmt = (fmt or "").strip()

    if prop == "postcode":
        return {"type": "string", "pattern": postcode_pattern, "maxLength": 8}
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


def describe(row_no, cells, marks, notes, unreferenced, profile, reference_name):
    parts = []
    if cells.get(profile.desc):
        parts.append(cells[profile.desc].rstrip("."))

    labels = {"M": "Mandatory", "O": "Optional", "CM": "Conditionally mandatory",
              "N/A": "Not applicable"}

    def summarise(pairs):
        grouped = OrderedDict()
        for case_type, mark in pairs:
            if mark:
                grouped.setdefault(mark, []).append(case_type)
        return "; ".join(f"{labels.get(m, m)}: {', '.join(v)}" for m, v in grouped.items())

    primary = summarise(marks.items())
    if primary:
        parts.append(primary)

    if cells.get(profile.rules):
        parts.append(f"Business rules: {cells[profile.rules].rstrip('.')}")
    if cells.get(profile.comment):
        parts.append(f"Comment: {cells[profile.comment].rstrip('.')}")
    if cells.get(profile.refdata[0]):
        parts.append(f"Ref data source: {cells[profile.refdata[0]]}")
    parts.extend(notes)
    if unreferenced:
        parts.append(f"NOT IN {reference_name} — no equivalent in the reference schema")
    parts.append(f"Sheet row {row_no}")
    return ". ".join(parts) + "."


def build(rows, reference, profile, reference_name, postcode_pattern):
    containers = {key: {"props": OrderedDict(), "required": [], "rows": {}} for key in CONTAINERS}
    report = {"unmapped": [], "unreferenced": [], "conflicts": [], "attachment_marks": {},
              "counts": {"total": len(rows), "blank": 0, "section": 0, "header": 0,
                         "field": 0, "structural": 0}}
    ref_props = reference_property_index(reference)

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
                report["attachment_marks"][ATTACHMENT_ROWS[lookup]] = (
                    all(m == "M" for m in marks.values() if m),
                    describe(row_no, cells, marks, [], False, profile, reference_name),
                )
            continue

        container_key, prop = mapping
        notes = []
        fragment = fragment_for(cells.get(profile.fmt, ""), prop, notes, postcode_pattern)
        values = coded_values(f"{cells.get(profile.rules, '')} {cells.get(profile.comment, '')}")
        if values and fragment.get("type") == "string" and len(values) > 1:
            if EMIT_CODE_ENUMS:
                fragment["enum"] = values
            else:
                notes.append(f"Documented values: {', '.join(values)} (not enforced as an enum here)")

        unreferenced = container_key in UNREFERENCED_CONTAINERS or prop not in ref_props
        marks = mandatoriness(cells, profile)
        fragment["description"] = describe(row_no, cells, marks, notes, unreferenced,
                                           profile, reference_name)
        if unreferenced:
            report["unreferenced"].append((row_no, section, name, prop))

        bucket = containers[container_key]
        if prop in bucket["props"]:
            report["conflicts"].append((row_no, container_key, prop, bucket["rows"][prop]))
            continue
        bucket["props"][prop] = fragment
        bucket["rows"][prop] = row_no
        # Required only if the row is marked, and every mark it does carry is `M`. A BLANK cell
        # means "not stated for this case type", not "optional" — the sheet leaves whole column
        # groups blank per section, so treating blank as non-mandatory would drop almost every
        # required field. Any O/CM/N/A anywhere disqualifies: a single shared schema cannot
        # demand a field that some case type says is optional or inapplicable.
        stated = [m for m in marks.values() if m]
        if stated and all(m == "M" for m in stated):
            bucket["required"].append(prop)

    return containers, report


def reference_postcode_pattern(schema):
    """The reference's address.postcode regex, following one level of local $ref.

    The canonical schema holds it behind a shared `ukGovPostCode` definition rather than inline,
    so the pattern has to be chased through the $ref instead of read straight off the property.
    """
    definitions = schema.get("definitions", {})
    node = definitions.get("address", {}).get("properties", {}).get("postcode", {})
    ref = node.get("$ref", "")
    if ref.startswith("#/definitions/"):
        node = definitions.get(ref.split("/")[-1], {})
    return node.get("pattern")


def reference_property_index(schema):
    """Property names reachable from the reference's `migratedCase` definition.

    Scoped rather than whole-document: the flattened canonical schema also carries the submission
    envelope and the outcome/error payloads, so an unscoped walk would count `fileName`, `payload`
    or `errorMessage` as case-model fields and quietly mark a genuinely novel LIBRA field as
    already present.

    Falls back to the whole document when there is no `migratedCase` definition, so an arbitrary
    --reference still works.
    """
    definitions = schema.get("definitions", {})
    root = definitions.get(REFERENCE_ROOT_DEFINITION, schema)

    found, seen = set(), set()

    def walk(node):
        if isinstance(node, dict):
            ref = node.get("$ref", "")
            if ref.startswith("#/definitions/"):
                name = ref.split("/")[-1]
                if name not in seen:
                    seen.add(name)
                    walk(definitions.get(name, {}))
            for key, value in node.items():
                if key == "properties" and isinstance(value, dict):
                    found.update(value)
                walk(value)
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(root)
    return found | REFERENCE_ALIASES


def assemble(containers, reference, workbook, profile, report):
    definitions = OrderedDict()

    # Child containers attach into their parent as $ref before parents are emitted.
    # A child whose own contents include a mandatory field makes the attachment itself
    # mandatory in the parent (this is how the canonical schema ends up requiring
    # caseDetails.prosecutor and defendant.offences). parentGuardianPerson is excluded: it attaches through a oneOf,
    # and `M` marks there mean "mandatory within the block if a guardian exists".
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
            is_required = explicit[0]   # the sheet's own row for this container wins

        ref = {"$ref": f"#/definitions/{def_name}"}
        attach = {"type": "array", "items": ref} if is_array else ref
        if is_array and is_required:
            attach["minItems"] = 1
        if explicit is not None:
            attach["description"] = explicit[1]
        containers[parent]["props"][prop] = attach
        if is_required:
            containers[parent]["required"].append(prop)
            if explicit is None:
                derived_required.append(f"{CONTAINERS[parent][0]}.{prop}")

    # parentGuardianInformation keeps the reference schema's oneOf(person | organisation).
    if containers["pgOrg"]["props"] and containers["pgPerson"]["props"]:
        containers["individual"]["props"]["parentGuardianInformation"] = {
            "description": "Parent Guardian Information — an individual or an organisation.",
            "oneOf": [{"$ref": "#/definitions/parentGuardianPerson"},
                      {"$ref": "#/definitions/parentGuardianOrganisation"}],
        }

    for key, (def_name, _parent, _prop, _is_array, strict) in CONTAINERS.items():
        bucket = containers[key]
        if not bucket["props"]:
            continue
        entry = OrderedDict([("type", "object"), ("properties", bucket["props"])])
        if bucket["required"]:
            entry["required"] = sorted(bucket["required"])
        if strict:
            entry["additionalProperties"] = False
        definitions[def_name] = entry

    report["derived_required"] = derived_required
    report["empty_attachments"] = empty_attachments

    for name in COPIED_DEFINITIONS:
        if name in reference.get("definitions", {}):
            definitions[name] = reference["definitions"][name]

    migrated_case = OrderedDict()
    for key, (def_name, parent, prop, is_array, _s) in CONTAINERS.items():
        if parent is not None or prop is None or not containers[key]["props"]:
            continue
        ref = {"$ref": f"#/definitions/{def_name}"}
        migrated_case[prop] = {"type": "array", "items": ref} if is_array else ref

    return OrderedDict([
        ("$schema", "http://json-schema.org/draft-04/schema#"),
        ("id", profile.schema_id),
        ("type", "object"),
        ("description",
         f"Migrated Case File Submission ({profile.name}) — generated from '{profile.sheet}' in "
         f"{Path(workbook).name} by tools/schema-gen/generate-dlrm-schema.py. "
         "Do not hand-edit; regenerate from the workbook. `required` is the intersection of the "
         f"sheet's {', '.join(profile.marks.values())} columns — per-case-type mandatoriness is "
         "in each field's description and must be enforced by the source-system validation "
         "rules, not here."),
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

STRUCTURAL_PROPERTIES = {
    "migratedCase", "caseDetails", "hearings", "defendants", "migrationSourceSystem", "offences",
    "individual", "personalInformation", "selfDefinedInformation", "parentGuardianInformation",
    "contactDetails", "address", "caseMarkers", "individualAliases", "listedDefendants",
    "alcoholRelatedOffence", "plea", "verdict", "allocationDecision", "prosecutor",
    "weekCommencingDate",
}


def print_report(report, containers, reference, out_path, wrote, profile, reference_name):
    ref_props = reference_property_index(reference)
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

    missing = sorted(ref_props - emitted - STRUCTURAL_PROPERTIES)
    if missing:
        print(f"\n  IN {reference_name}, ABSENT FROM THE SHEET ({len(missing)}) — reverse delta:")
        print("    " + ", ".join(missing))

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


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--workbook", default=str(DEFAULT_WORKBOOK))
    ap.add_argument("--sheet", help="override the sheet name")
    ap.add_argument("--reference", default=str(DEFAULT_REFERENCE),
                    help="schema to copy shared primitives from and flag novel fields against "
                         "(default: the flattened canonical schema — generate it first with "
                         "flatten-canonical-schema.py)")
    ap.add_argument("--out-dir", default=".",
                    help="directory to write the schema into (default: current directory)")
    ap.add_argument("--out", help="explicit output path; overrides --out-dir")
    ap.add_argument("--compare", metavar="SCHEMA",
                    help="also diff the generated schema against an existing one")
    ap.add_argument("--dry-run", action="store_true", help="report only, write nothing")
    args = ap.parse_args()

    profile = PROFILE
    sheet = args.sheet or profile.sheet
    out_path = Path(args.out) if args.out else Path(args.out_dir) / profile.out

    for path in (args.workbook, args.reference):
        if not Path(path).exists():
            sys.exit(f"error: not found: {path}")

    reference = json.loads(Path(args.reference).read_text(encoding="utf-8"))
    reference_name = Path(args.reference).name

    postcode_pattern = reference_postcode_pattern(reference)
    if not postcode_pattern:
        sys.exit(f"error: no address.postcode pattern in {args.reference}")

    rows = read_sheet(args.workbook, sheet)
    containers, report = build(rows, reference, profile, reference_name, postcode_pattern)
    schema = assemble(containers, reference, args.workbook, profile, report)

    if not args.dry_run:
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(schema, indent=2, ensure_ascii=False) + "\n",
                            encoding="utf-8")

    print_report(report, containers, reference, out_path, not args.dry_run, profile, reference_name)

    if args.compare:
        if not Path(args.compare).exists():
            sys.exit(f"error: not found: {args.compare}")
        compare(schema, json.loads(Path(args.compare).read_text(encoding="utf-8")), args.compare)


if __name__ == "__main__":
    main()
