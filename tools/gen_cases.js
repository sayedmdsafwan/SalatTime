const { computeDay } = require('./astro_engine.js');
const cases = [
  { name:"dhaka_karachi_hanafi",   lat:23.8103, lon:90.4125, tz:6,  y:2026,m:9,d:15,  madhhab:"hanafi", method:"karachi" },
  { name:"dhaka_solstice_summer",  lat:23.8103, lon:90.4125, tz:6,  y:2026,m:6,d:21,  madhhab:"hanafi", method:"karachi" },
  { name:"dhaka_solstice_winter",  lat:23.8103, lon:90.4125, tz:6,  y:2026,m:12,d:21, madhhab:"hanafi", method:"karachi" },
  { name:"mecca_makkah90_shafi",   lat:21.4225, lon:39.8262, tz:3,  y:2026,m:3,d:1,   madhhab:"shafi",  method:"makkah90" },
  { name:"london_mwl_shafi",       lat:51.5074, lon:-0.1278, tz:0,  y:2026,m:1,d:15,  madhhab:"shafi",  method:"mwl" },
  { name:"newyork_isna_shafi",     lat:40.7128, lon:-74.0060, tz:-5, y:2026,m:1,d:15, madhhab:"shafi",  method:"isna" },
  { name:"sydney_mwl_shafi",       lat:-33.8688, lon:151.2093, tz:11, y:2026,m:6,d:21,madhhab:"shafi",  method:"mwl" },
  { name:"reykjavik_highlat_mwl",  lat:64.1466, lon:-21.9426, tz:0,  y:2026,m:6,d:21, madhhab:"shafi",  method:"mwl" }
];
for(const c of cases){
  const r = computeDay(c.y, c.m, c.d, c.lat, c.lon, c.tz, c.madhhab, c.method);
  console.log(c.name, JSON.stringify(r));
}
