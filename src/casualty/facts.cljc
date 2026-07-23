(ns casualty.facts
  "Per-jurisdiction non-life (property & casualty) insurance underwriting
  requirement catalog -- the G2-style spec-basis table the Non-Life
  Insurance Governor checks every jurisdiction/assess proposal against
  ('did the advisor cite an OFFICIAL public source for this
  jurisdiction's requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  `cloud-itonami-isic-6511`'s `underwriting.facts` uses: a jurisdiction
  not in this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official insurance
  regulator (see `:provenance`); they are a STARTING catalog, not a
  from-scratch survey of all ~194 jurisdictions. Extending coverage is
  additive: add one map to `catalog`, cite a real source, done -- never
  invent a jurisdiction's requirements to make coverage look bigger.

  Deliberately a SEPARATE catalog from `underwriting.facts` even where
  the same regulator/law governs both lines (e.g. Japan's 保険業法 covers
  life AND non-life) -- the required-docs a property/casualty applicant
  submits (risk inspection, loss history, insurable-interest proof) are
  genuinely different from a life applicant's (health declaration,
  beneficiary designation), so a shared catalog would blur two distinct
  regulatory checklists.")

(def catalog
  "iso3 -> requirement map. `:required-docs` mirrors the generic
  non-life underwriting checklist a P&C insurer asks for in some form;
  `:legal-basis` / `:owner-authority` / `:provenance` are the G2
  citation the governor requires before any :jurisdiction/assess
  proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "金融庁 (Financial Services Agency)"
          :legal-basis "保険業法 (Insurance Business Act)"
          :national-spec "損害保険料率算出機構 参考純率 (General Insurance Rating Organization of Japan reference loss-cost rates)"
          :provenance "https://www.fsa.go.jp/"
          :required-docs ["申込書 (application form)"
                          "本人確認書類"
                          "保険の目的の明細 (insured-property/risk description)"
                          "損害保険金額の根拠資料 (basis for the requested coverage amount)"]}
   "USA-NY" {:name "United States -- New York (exemplar; federalism note below)"
             :owner-authority "New York State Department of Financial Services (NYDFS)"
             :legal-basis "New York Insurance Law Article 34 (Rate Making)"
             :national-spec "NYDFS property/casualty insurance regulation"
             :provenance "https://www.dfs.ny.gov/"
             :notes "No federal insurance regulator -- property/casualty insurance is regulated per-state; New York is an exemplar, not a national authority."
             :required-docs ["Application for property/casualty coverage"
                             "Proof of insurable interest"
                             "Property/risk inspection or valuation report"
                             "Prior loss-history report (CLUE or equivalent)"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Financial Conduct Authority (FCA) / Prudential Regulation Authority (PRA)"
          :legal-basis "Financial Services and Markets Act 2000"
          :national-spec "FCA ICOBS (Insurance: Conduct of Business Sourcebook)"
          :provenance "https://www.fca.org.uk/"
          :required-docs ["Application/proposal form"
                          "Statement of insurable interest"
                          "Risk survey / valuation"
                          "Insurance Product Information Document (IPID)"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundesanstalt für Finanzdienstleistungsaufsicht (BaFin)"
          :legal-basis "Versicherungsaufsichtsgesetz (VAG)"
          :national-spec "VVG-Informationspflichtenverordnung (disclosure-duties regulation)"
          :provenance "https://www.bafin.de/"
          :required-docs ["Antragsformular (application form)"
                          "Nachweis des versicherbaren Interesses (insurable-interest proof)"
                          "Risikobesichtigung/Wertgutachten (risk inspection/valuation)"
                          "Produktinformationsblatt (product information sheet)"]}
   "KEN" {:name "Kenya"
          :owner-authority "Insurance Regulatory Authority (IRA)"
          :legal-basis "Insurance Act (Cap. 487)"
          :national-spec "Insurance (Valuation of Technical Provisions for General Insurance Business) Guidelines, 2017 (Legal Notice 37 of 2017)"
          :provenance "https://ira.go.ke/"
          :notes "Same-Act-covers-both-lines case (like Japan's 保険業法): the Insurance Act (Cap. 487) s.2 defines \"general insurance business\" as \"insurance business of any class or classes not being long term insurance business\", and s.26(1) requires an insurer licensed for both lines to hold separate licenses per class -- but the SOLVENCY figures genuinely diverge per class. The Second Schedule (s. 23(1)) 'MINIMUM CAPITAL REQUIREMENTS' sets general (non-life) insurance business paid-up capital at the higher of KES 600 million / risk-based capital / 20% of net earned premiums of the preceding financial year -- a different figure and formula from long term (life) business (KES 400 million / risk-based capital / 5% of life-business liabilities). s.41(2) (Part V -- Assets, Liabilities, Solvency Margins and Investments) separately requires 'An insurer carrying on both long term and general insurance business shall at all times maintain separate capital adequacy ratios.' Verbatim text fetched and read this session from new.kenyalaw.org's Insurance Act consolidation (eng@2023-12-11) and its Legal Notice 37 of 2017 consolidation (eng@2022-12-31)."
          :required-docs ["Proposal form / application for general insurance cover"
                          "Proof of insurable interest (Insurance Act (Cap. 487) s. 94(1): \"no policy of insurance shall be issued on the life or lives of any person or persons, or on any other event or events whatsoever, wherein the person or persons for whose use, benefit, or on whose account such policy or policies shall be made, shall have no insurable interest\")"
                          "Risk survey / valuation report"
                          "Policy document / statement of insurance particulars"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to bind coverage on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-6512 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `casualty.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-docs-satisfied?
  "Does `submitted` (a set/coll of doc keywords or strings) satisfy every
  required doc listed for `iso3`? Missing spec-basis -> never satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-docs]} (spec-basis iso3)]
    (let [need (count required-docs)
          have (count (filter (set submitted) required-docs))]
      (= need have))))

(defn doc-checklist [iso3]
  (:required-docs (spec-basis iso3) []))
