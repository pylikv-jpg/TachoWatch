package com.pylikv.tachowatch

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CounterRegressionTest {
    @Test fun shiftUsesFreshSeedAndOnlyAddsIndependentDrivingDelta(){
        val c=ShiftDrivingCounter();c.seed(320)
        c.update(3000,"2026-37",0);assertEquals(320,c.minutes)
        c.update(3041,"2026-37",0);assertEquals(361,c.minutes)
        c.update(3041,"2026-37",0);assertEquals(361,c.minutes)
    }
    @Test fun missedResponseDoesNotDiscardTheLastGoodBaseline(){
        val c=ShiftDrivingCounter();c.seed(100);c.update(3000,"2026-37",0)
        c.update(null,"2026-37",0);c.update(3005,"2026-37",0)
        assertEquals(105,c.minutes)
    }
    @Test fun dailyRestClearsOldShiftAndNextDrivingStartsAtZero(){
        val c=ShiftDrivingCounter();c.seed(388)
        c.update(3000,"2026-37",540);assertEquals(0,c.minutes)
        c.update(3006,"2026-37",0);assertEquals(6,c.minutes)
    }
    @Test fun rolloverCannotTurnTwoWeekDecreaseIntoDriving(){
        val c=ShiftDrivingCounter();c.seed(100);c.update(4000,"2026-36",0)
        c.update(2000,"2026-37",0);assertNull(c.minutes)
    }
    @Test fun incompleteDebtCannotBePaidInSmallPieces(){
        val days=listOf(
            HistoryData.Day("2026-09-05",60,0,0,"08:00",null,"10:00",null),
            HistoryData.Day("2026-09-06",20,0,0,"22:40",null,"23:00",null),
            HistoryData.Day("2026-09-07",8,0,0,"14:52",null,"15:00",null)
        )
        val rest=HistoryData.buildRestInfo(days).first()
        assertEquals(36*60+40,rest.actualMinutes)
        assertEquals(500,rest.compensationCreatedMinutes)
        assertEquals(500,rest.compensationRemainingMinutes) // formerly 208 (3:28)
        val full=HistoryData.buildRestInfo(days+HistoryData.Day("2026-09-08",60,0,0,"10:20",null,"11:20",null)).first()
        assertEquals(0,full.compensationRemainingMinutes)
    }
    @Test fun cardShiftCrossesMidnightAndIncludesOpenDrivingOnlyUntilSnapshot(){
        val text="""
DAY#1 date=2026-09-08
  00:00 REST
  22:00 WORK
  23:00 DRIVING
DAY#2 date=2026-09-09
  00:00 DRIVING
  00:30 REST
  01:15 DRIVING
STATUS=OK
""".trimIndent()
        val snapshot=CardActivityTimeline.parse(text,Instant.parse("2026-09-09T02:00:00Z").toEpochMilli())
        assertEquals(135,snapshot.shiftDriving) // 90 + 45, not last day's subtotal.
    }
    @Test fun weeklyRestEndComesFromActivityTime(){
        val text="""
DAY#1 date=2026-09-05
  00:00 WORK
  10:00 REST
DAY#2 date=2026-09-06
  00:00 REST
DAY#3 date=2026-09-07
  00:00 REST
  03:17 WORK
  04:00 DRIVING
STATUS=OK
""".trimIndent()
        val snapshot=CardActivityTimeline.parse(text,Instant.parse("2026-09-07T05:00:00Z").toEpochMilli())
        assertEquals(Instant.parse("2026-09-07T03:17:00Z").toEpochMilli(),snapshot.lastWeeklyRestEnd)
    }
}
