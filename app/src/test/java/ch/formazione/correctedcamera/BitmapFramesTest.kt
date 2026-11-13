package ch.formazione.correctedcamera
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.widget.ImageView
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitmapFramesTest {
 private fun pixels()=ByteBuffer.wrap(byteArrayOf(-1,0,0,-1,0,-1,0,-1))
 @Test fun tightlyPackedFrameRemainsUsable() {
  val frame=BitmapFrames.fromRgba(pixels(),2,1,8,4,0)
  assertFalse(frame.isRecycled);assertEquals(Color.RED,frame.getPixel(0,0));assertEquals(Color.GREEN,frame.getPixel(1,0))
  val output=Bitmap.createBitmap(2,1,Bitmap.Config.ARGB_8888);Canvas(output).drawBitmap(frame,0f,0f,null)
  assertEquals(Color.RED,output.getPixel(0,0));frame.recycle();output.recycle()
 }
 @Test fun rotationKeepsBothPixelsAndChangesDimensions() {
  val frame=BitmapFrames.fromRgba(pixels(),2,1,8,4,90)
  assertEquals(1,frame.width);assertEquals(2,frame.height);assertFalse(frame.isRecycled)
  assertEquals(setOf(Color.RED,Color.GREEN),setOf(frame.getPixel(0,0),frame.getPixel(0,1)));frame.recycle()
 }
 @Test fun slowUiDropsPendingFramesAndNeverRecyclesDisplayedBitmap() {
  val view=ImageView(RuntimeEnvironment.getApplication());val presenter=BitmapPresenter {view}
  val frames=(1..100).map {Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)}
  frames.forEach {presenter.offer(it)};assertTrue(frames.dropLast(1).all{it.isRecycled});assertFalse(frames.last().isRecycled)
  Shadows.shadowOf(Looper.getMainLooper()).idle()
  val next=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);presenter.offer(next);Shadows.shadowOf(Looper.getMainLooper()).idle()
  assertFalse(frames.last().isRecycled);assertFalse(next.isRecycled)
  presenter.close();val rejected=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);presenter.offer(rejected);assertTrue(rejected.isRecycled)
 }
}
