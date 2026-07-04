-- The V2 seed's second wave of patients (PX-2086..PX-2121) repeats the first wave's names
-- exactly: both name arrays in the generator are length 40, so the (first, last) pairing
-- cycles every 40 patients — the queue looked full of duplicate people (same name, different
-- MRN). This re-pairs the second wave's surnames using the generator's corrected formula
-- (last = LAST_NAMES[(idx*7 + 3 + 11) % 40]); every full name is unique afterwards.
update patients set last_name = 'Okafor' where demo_mrn = 'PX-2086';
update patients set last_name = 'Whitlock' where demo_mrn = 'PX-2087';
update patients set last_name = 'Lindgren' where demo_mrn = 'PX-2088';
update patients set last_name = 'Dimitriou' where demo_mrn = 'PX-2089';
update patients set last_name = 'Calderon' where demo_mrn = 'PX-2090';
update patients set last_name = 'Adeyemi' where demo_mrn = 'PX-2091';
update patients set last_name = 'Romano' where demo_mrn = 'PX-2092';
update patients set last_name = 'Sandoval' where demo_mrn = 'PX-2093';
update patients set last_name = 'Ng' where demo_mrn = 'PX-2094';
update patients set last_name = 'Yamamoto' where demo_mrn = 'PX-2095';
update patients set last_name = 'Eskildsen' where demo_mrn = 'PX-2096';
update patients set last_name = 'Vega' where demo_mrn = 'PX-2097';
update patients set last_name = 'Delacroix' where demo_mrn = 'PX-2098';
update patients set last_name = 'Mensah' where demo_mrn = 'PX-2099';
update patients set last_name = 'Schreiber' where demo_mrn = 'PX-2100';
update patients set last_name = 'Salgado' where demo_mrn = 'PX-2101';
update patients set last_name = 'Bjornson' where demo_mrn = 'PX-2102';
update patients set last_name = 'Navarro' where demo_mrn = 'PX-2103';
update patients set last_name = 'Haddad' where demo_mrn = 'PX-2104';
update patients set last_name = 'Aziz' where demo_mrn = 'PX-2105';
update patients set last_name = 'Forsythe' where demo_mrn = 'PX-2106';
update patients set last_name = 'Nwosu' where demo_mrn = 'PX-2107';
update patients set last_name = 'Hollis' where demo_mrn = 'PX-2108';
update patients set last_name = 'Lindqvist' where demo_mrn = 'PX-2109';
update patients set last_name = 'Bauer' where demo_mrn = 'PX-2110';
update patients set last_name = 'Castellano' where demo_mrn = 'PX-2111';
update patients set last_name = 'Abara' where demo_mrn = 'PX-2112';
update patients set last_name = 'Rhodes' where demo_mrn = 'PX-2113';
update patients set last_name = 'Larsson' where demo_mrn = 'PX-2114';
update patients set last_name = 'Cho' where demo_mrn = 'PX-2115';
update patients set last_name = 'Kovac' where demo_mrn = 'PX-2116';
update patients set last_name = 'Oyelaran' where demo_mrn = 'PX-2117';
update patients set last_name = 'Brennan' where demo_mrn = 'PX-2118';
update patients set last_name = 'Marchetti' where demo_mrn = 'PX-2119';
update patients set last_name = 'Ellis' where demo_mrn = 'PX-2120';
update patients set last_name = 'Ferreira' where demo_mrn = 'PX-2121';
