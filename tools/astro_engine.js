// Extracted copy of the Astro engine used by both index.html and Astro.kt.
// Kept here ONLY for the regression tests in verify_prayer_times.js — this
// is not loaded by the app itself. If you change the math in index.html
// or Astro.kt, mirror the change here too before re-generating goldens.
const D2R = Math.PI/180, R2D = 180/Math.PI;
const dsin = d => Math.sin(d*D2R);
const dcos = d => Math.cos(d*D2R);
const dtan = d => Math.tan(d*D2R);
const darcsin = x => Math.asin(x)*R2D;
const darccos = x => Math.acos(Math.max(-1,Math.min(1,x)))*R2D;
const darccot = x => Math.atan2(1,x)*R2D;
const fixAngle = a => { a = a - 360*Math.floor(a/360); return a<0?a+360:a; };
const fixHour = a => { a = a - 24*Math.floor(a/24); return a<0?a+24:a; };

function julianDate(year, month, day){
  if (month<=2){ year -=1; month +=12; }
  const A = Math.floor(year/100);
  const B = 2 - A + Math.floor(A/4);
  return Math.floor(365.25*(year+4716)) + Math.floor(30.6001*(month+1)) + day + B - 1524.5;
}
function sunPosition(jd){
  const Dd = jd - 2451545.0;
  const g = fixAngle(357.529 + 0.98560028*Dd);
  const q = fixAngle(280.459 + 0.98564736*Dd);
  const L = fixAngle(q + 1.915*dsin(g) + 0.020*dsin(2*g));
  const e = 23.439 - 0.00000036*Dd;
  const RA = Math.atan2(dcos(e)*dsin(L), dcos(L))*R2D/15;
  const eqt = q/15 - fixHour(RA);
  const decl = darcsin(dsin(e)*dsin(L));
  return { decl, eqt };
}
function dhuhrTime(jDate, lng, tz){
  let noon = 12 - lng/15 + tz;
  for(let i=0;i<2;i++){
    const jd = jDate + noon/24 - tz/24;
    const {eqt} = sunPosition(jd);
    noon = 12 - eqt - lng/15 + tz;
  }
  return noon;
}
function sunAngleTime(jDate, lat, lng, tz, angle, ccw){
  const noon = dhuhrTime(jDate, lng, tz);
  const jd = jDate + noon/24 - tz/24;
  const {decl} = sunPosition(jd);
  const num = -dsin(angle) - dsin(lat)*dsin(decl);
  const den = dcos(lat)*dcos(decl);
  const cosH = num/den;
  if (cosH > 1 || cosH < -1) return null;
  const H = darccos(cosH)/15;
  return ccw ? noon - H : noon + H;
}
function asrTime(jDate, lat, lng, tz, factor){
  const noon = dhuhrTime(jDate, lng, tz);
  const jd = jDate + noon/24 - tz/24;
  const {decl} = sunPosition(jd);
  const angle = -darccot(factor + dtan(Math.abs(lat-decl)));
  return sunAngleTime(jDate, lat, lng, tz, angle, false);
}
const PRAYER_METHODS = {
  karachi: { fajr: 18,   ishaAngle: 18   },
  mwl:     { fajr: 18,   ishaAngle: 17   },
  isna:    { fajr: 15,   ishaAngle: 15   },
  egypt:   { fajr: 19.5, ishaAngle: 17.5 },
  tehran:  { fajr: 17.7, ishaAngle: 14   },
  makkah90:  { fajr: 18.5, ishaMinutes: 90  },
  makkah120: { fajr: 18.5, ishaMinutes: 120 }
};
function computeDay(year, month, day, lat, lon, tz, madhhab, methodKey){
  const jDate = julianDate(year, month, day);
  const factor = madhhab === "hanafi" ? 2 : 1;
  const m = PRAYER_METHODS[methodKey] || PRAYER_METHODS.karachi;
  let sunrise = sunAngleTime(jDate, lat, lon, tz, 0.833, true);
  let maghrib = sunAngleTime(jDate, lat, lon, tz, 0.833, false);
  let fajr = sunAngleTime(jDate, lat, lon, tz, m.fajr, true);
  let isha = typeof m.ishaAngle === "number"
    ? sunAngleTime(jDate, lat, lon, tz, m.ishaAngle, false)
    : (maghrib !== null && typeof m.ishaMinutes === "number" ? maghrib + m.ishaMinutes/60 : null);
  if(sunrise === null || maghrib === null){
    const clampLat = lat < 0 ? -48 : 48;
    if(sunrise === null) sunrise = sunAngleTime(jDate, clampLat, lon, tz, 0.833, true);
    if(maghrib === null) maghrib = sunAngleTime(jDate, clampLat, lon, tz, 0.833, false);
    if(isha === null && typeof m.ishaAngle !== "number" && maghrib !== null && typeof m.ishaMinutes === "number") isha = maghrib + m.ishaMinutes/60;
  }
  if((fajr === null || isha === null) && sunrise !== null && maghrib !== null){
    let nightLength = 24 - (fixHour(maghrib) - fixHour(sunrise));
    if(nightLength <= 0 || nightLength > 24) nightLength = 8;
    const portion = nightLength / 7;
    if(fajr === null) fajr = sunrise - portion;
    if(isha === null) isha = maghrib + portion;
  }
  if(fajr === null || isha === null){
    const clampLat = lat < 0 ? -48 : 48;
    if(fajr === null) fajr = sunAngleTime(jDate, clampLat, lon, tz, m.fajr, true);
    if(isha === null && typeof m.ishaAngle === "number") isha = sunAngleTime(jDate, clampLat, lon, tz, m.ishaAngle, false);
    if(isha === null && maghrib !== null && typeof m.ishaMinutes === "number") isha = maghrib + m.ishaMinutes/60;
  }
  return { fajr, sunrise, dhuhr: dhuhrTime(jDate, lon, tz), asr: asrTime(jDate, lat, lon, tz, factor), maghrib, isha };
}
module.exports = { computeDay };
