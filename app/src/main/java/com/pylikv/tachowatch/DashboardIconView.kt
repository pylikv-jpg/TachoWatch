package com.pylikv.tachowatch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Monochrome tachograph pictograms; consistent across Android emoji fonts. */
class DashboardIconView(context:Context, private val kind:String, color:Int):View(context){
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;style=Paint.Style.STROKE;strokeWidth=2.5f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        canvas.save();canvas.scale(width/32f,height/32f)
        when(kind){
            "drive"->{canvas.drawCircle(16f,16f,12f,pen);canvas.drawCircle(16f,16f,3f,pen);canvas.drawLine(4f,13f,13f,15f,pen);canvas.drawLine(28f,13f,19f,15f,pen);canvas.drawLine(16f,19f,16f,28f,pen)}
            "work"->{
                canvas.drawLine(6f,27f,23f,8f,pen);canvas.drawLine(26f,27f,9f,8f,pen)
                canvas.drawLine(5f,7f,12f,13f,pen);canvas.drawLine(20f,13f,27f,7f,pen)
                canvas.drawLine(5f,7f,9f,3f,pen);canvas.drawLine(27f,7f,23f,3f,pen)
            }
            "rest"->{canvas.drawLine(3f,6f,3f,27f,pen);canvas.drawLine(3f,21f,29f,21f,pen);canvas.drawLine(29f,15f,29f,27f,pen);canvas.drawCircle(9f,14f,3f,pen);canvas.drawRoundRect(15f,12f,28f,20f,2f,2f,pen)}
            "available"->{canvas.drawRect(5f,5f,27f,27f,pen);canvas.drawLine(6f,26f,26f,6f,pen)}
            else->{
                canvas.drawLine(6f,3f,26f,3f,pen);canvas.drawLine(6f,29f,26f,29f,pen)
                val path=Path().apply{moveTo(8f,4f);cubicTo(8f,13f,24f,19f,24f,28f);lineTo(8f,28f);cubicTo(8f,19f,24f,13f,24f,4f);close()};canvas.drawPath(path,pen)
            }
        }
        canvas.restore()
    }
}
