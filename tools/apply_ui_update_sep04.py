from pathlib import Path

p=Path('app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt')
s=p.read_text(encoding='utf-8')
if 'UI_UPDATE_SEP04' in s:
    raise SystemExit(0)

s=s.replace('activitySub=sub("ожидание онлайн-данных");a.third.addView(activitySub)', 'activitySub=sub("ожидание онлайн-данных").apply{textSize=15f};a.third.addView(activitySub) // UI_UPDATE_SEP04', 1)
s=s.replace('shiftDrivingSub=sub("онлайн");s.third.addView(shiftDrivingSub)', 'shiftDrivingSub=sub("онлайн").apply{textSize=15f;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD)};s.third.addView(shiftDrivingSub)', 1)
s=s.replace('activityTime.text=HistoryData.fmt(restCredited)', 'activityTime.text=HistoryData.fmt(restActual)', 1)
s=s.replace('activitySub.text=if(restNext!=null)"засчитано ${HistoryData.fmt(restCredited)} • фактически ${HistoryData.fmt(restActual)} • следующая ступень ${HistoryData.fmt(restNext)}" else "засчитано 45:00 • фактически ${HistoryData.fmt(restActual)}"', 'activitySub.text=if(restNext!=null)"Засчитано: ${HistoryData.fmt(restCredited)}   •   Следующая ступень: ${HistoryData.fmt(restNext)}" else "Засчитано: ${HistoryData.fmt(restCredited)}";activitySub.setTextColor(BG);activitySub.setTypeface(activitySub.typeface,Typeface.BOLD);activitySub.background=rounded(0xFFE7F3FA.toInt(),dp(8).toFloat());activitySub.setPadding(dp(8),dp(5),dp(8),dp(5))', 1)
s=s.replace('        }else{\n            activityTitle.text="$icon  ТЕКУЩАЯ ДЕЯТЕЛЬНОСТЬ • $currentActivity"', '        }else{\n            activitySub.background=null;activitySub.setTextColor(MUTED);activitySub.setTypeface(Typeface.DEFAULT,Typeface.NORMAL);activitySub.setPadding(0,dp(2),0,0)\n            activityTitle.text="$icon  ТЕКУЩАЯ ДЕЯТЕЛЬНОСТЬ • $currentActivity"', 1)
s=s.replace('weekSub.text="прошлая неделя ${HistoryData.fmt(prev)} • 10-часовых смен: ${currentWeekTenHourUses()}/2"', 'weekSub.text="прошлая неделя ${HistoryData.fmt(prev)}"', 1)
s=s.replace('shiftDrivingSub.text=if(remain<=30)"⚠ Осталось ${HistoryData.fmt(remain)} вождения" else "осталось ${HistoryData.fmt(remain)} • онлайн"', 'val tenUses=currentWeekTenHourUses();shiftDrivingSub.text=(if(remain<=30)"⚠ Осталось ${HistoryData.fmt(remain)}" else "Осталось ${HistoryData.fmt(remain)}")+" • 10-часовые вождения: ${tenUses} из 2 использовано"', 1)

anchor='    private fun currentWeekTenHourUses():Int{val now=isoCalendar(Date());return history?.days.orEmpty().count{d->val date=parseDateOnly(d.date)?:return@count false;val c=isoCalendar(date);c.get(Calendar.WEEK_OF_YEAR)==now.get(Calendar.WEEK_OF_YEAR)&&c.getWeekYear()==now.getWeekYear()&&d.drivingMinutes>540}}\n'
s=s.replace(anchor, anchor+'    private fun currentReducedDailyRestUses():Int?{val rests=history?.rests.orEmpty();val lastWeekly=rests.indexOfLast{it.weekly};if(lastWeekly<0)return null;return rests.drop(lastWeekly+1).count{!it.weekly&&it.creditedDailyMinutes==9*60}.coerceAtMost(3)}\n',1)

hist='historyRoot.removeAllViews();val scroll=ScrollView(this);val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val days=history?.days?.takeLast(56)?.asReversed().orEmpty();c.addView(sub("История карты: ${days.size} из 56 дней"))'
rep='historyRoot.removeAllViews();val scroll=ScrollView(this);val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val days=history?.days?.takeLast(56)?.asReversed().orEmpty();val ab=card();ab.addView(label("ДОСТУПНЫЕ ИСКЛЮЧЕНИЯ"));val ru=currentReducedDailyRestUses();ab.addView(value(if(ru==null)"🛏 9-часовые отдыхи: данных недостаточно" else "🛏 9-часовые отдыхи: осталось ${(3-ru).coerceIn(0,3)} из 3",16f));val tu=currentWeekTenHourUses();ab.addView(value("🚗 10-часовые вождения: осталось ${(2-tu).coerceIn(0,2)} из 2",16f));ab.addView(sub("9 ч — между недельными отдыхами • 10 ч — пн–вс"));c.addView(ab);c.addView(space(8));c.addView(sub("История карты: ${days.size} из 56 дней"))'
s=s.replace(hist,rep,1)

p.write_text(s,encoding='utf-8')
print('Applied UI update Sep 04')
