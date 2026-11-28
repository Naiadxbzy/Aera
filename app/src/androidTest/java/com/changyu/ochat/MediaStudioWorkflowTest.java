package com.changyu.ochat;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Base64;
import android.widget.AdapterView;
import android.view.inspector.WindowInspector;
import androidx.test.platform.app.InstrumentationRegistry;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion=37)
public class MediaStudioWorkflowTest {
    private static String png() {
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(Color.CYAN);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes));
            return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
        } finally { bitmap.recycle(); }
    }

    @Test public void imageSubmissionSurvivesRotationAndSupportsExplicitRetry() throws Exception {
        try (RegressionPreferences state=new RegressionPreferences(); Fixture net=new Fixture()) {
            String session=UUID.randomUUID().toString();
            configure(state,net);
            MediaGenerationStore store=new MediaGenerationStore(state.context);
            try (ActivityScenario<MediaStudioActivity> page=ActivityScenario.launch(MediaStudioActivity.intent(state.context,session,"fixture"))) {
                page.onActivity(activity->{
                    ((EditText) find(activity.getWindow().getDecorView(),"生成提示词")).setText("图像测试");
                    find(activity.getWindow().getDecorView(),"生成图片").performClick();
                });
                assertTrue("Local image request not received",net.received.await(8,TimeUnit.SECONDS));
                page.recreate();
                page.onActivity(activity->assertEquals("图像测试",((EditText)find(activity.getWindow().getDecorView(),"生成提示词")).getText().toString()));
                net.release.countDown();
                JSONObject failure=awaitState(store,session,"failed");
                assertTrue(failure.optString("detail").contains("fixture"));
                tap(page,"重新生成");
                tapDialog("生成");
                JSONObject done=awaitState(store,session,"complete");
                assertEquals("image/png",done.optString("mime"));
                assertNotNull(BitmapFactory.decodeFile(store.resultFile(done.getString("id")).getPath()));
                assertEquals(2,net.posts.get());
                assertEquals(2,store.list(session).size());
                tap(page,"预览");
            } finally {
                net.release.countDown();
                clear(store,session);
            }
        }
    }

    @Test public void stoppedVideoCanResumeByRemoteIdWithoutSubmittingTwice() throws Exception {
        try (RegressionPreferences state=new RegressionPreferences(); Fixture net=new Fixture()) {
            String session=UUID.randomUUID().toString();
            configure(state,net);
            MediaGenerationStore store=new MediaGenerationStore(state.context);
            try(ActivityScenario<MediaStudioActivity> page=ActivityScenario.launch(MediaStudioActivity.intent(state.context,session,"fixture"))) {
                tap(page,"图片");
                tapDialog("视频");
                tapDialog("确认");
                page.onActivity(activity->{
                    View root=activity.getWindow().getDecorView();
                    ((EditText)find(root,"模型 ID",EditText.class)).setText("local-video");
                    ((EditText)find(root,"生成提示词")).setText("视频测试");
                    find(root,"生成视频").performClick();
                });
                long end=SystemClock.elapsedRealtime()+8000;
                while(SystemClock.elapsedRealtime()<end) {
                    List<JSONObject> records=store.list(session);
                    if(!records.isEmpty() && "job01".equals(records.get(0).optString("remoteId")))break;
                    SystemClock.sleep(40);
                }
                tap(page,"停止");
                JSONObject stopped=awaitState(store,session,"stopped");
                assertEquals("job01",stopped.optString("remoteId"));
                page.recreate();
                net.videoReady=true;
                tap(page,"继续获取");
                JSONObject complete=awaitState(store,session,"complete");
                assertEquals(stopped.getString("id"),complete.getString("id"));
                assertEquals("video/mp4",complete.getString("mime"));
                assertEquals(1,net.posts.get());
                assertEquals(1,store.list(session).size());
            } finally {clear(store,session);}
        }
    }

    private static void configure(RegressionPreferences state,Fixture fixture) throws Exception {
        JSONObject provider=new JSONObject().put("id","fixture").put("name","本地测试")
                .put("protocol","chat_completions").put("api","http://localhost:"+fixture.server.getLocalPort()+"/v1")
                .put("models",new JSONArray());
        state.prefs.edit().putString("providers",new JSONArray().put(provider).toString()).commit();
    }
    private static void clear(MediaGenerationStore store,String session) throws Exception {
        for(JSONObject j:store.list(session)) {
            String id=j.getString("id");
            MediaGenerationService.stop(id);
            long until=SystemClock.elapsedRealtime()+5000;
            while(MediaGenerationService.active(id) && SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);
            store.delete(id);
        }
    }
    private static JSONObject awaitState(MediaGenerationStore store,String session,String expected) {
        long until=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<until) {
            for(JSONObject j:store.list(session))
                if(expected.equals(j.optString("state")) && !MediaGenerationService.active(j.optString("id")))return j;
            SystemClock.sleep(40);
        }
        throw new AssertionError("Missing "+expected+": "+store.list(session));
    }
    private static void tap(ActivityScenario<MediaStudioActivity> page,String text) {
        long until=SystemClock.elapsedRealtime()+8000;
        boolean[] tapped={false};
        while(SystemClock.elapsedRealtime()<until) {
            page.onActivity(activity->{View v=find(activity.getWindow().getDecorView(),text);if(v!=null)tapped[0]=v.performClick();});
            if(tapped[0])return;
            SystemClock.sleep(40);
        }
        throw new AssertionError("Missing action "+text);
    }
    private static void tapDialog(String label) {
        Instrumentation test = InstrumentationRegistry.getInstrumentation();
        long until = SystemClock.elapsedRealtime() + 5000;
        boolean[] clicked = {false};
        while (!clicked[0] && SystemClock.elapsedRealtime() < until) {
            test.runOnMainSync(() -> {
                List<View> windows = WindowInspector.getGlobalWindowViews();
                for (int i = windows.size() - 1; i >= 0; i--) {
                    View candidate = find(windows.get(i), label);
                    if (candidate == null || !candidate.isShown()) continue;
                    for (View item = candidate; item != null; ) {
                        if (item.getParent() instanceof AdapterView<?>) {
                            AdapterView<?> list = (AdapterView<?>) item.getParent();
                            int position = list.getPositionForView(item);
                            if (position >= 0 && list.performItemClick(item, position, list.getItemIdAtPosition(position))) {
                                clicked[0] = true;
                                return;
                            }
                        }
                        if (item.isClickable() && item.isEnabled() && item.performClick()) {
                            clicked[0] = true;
                            return;
                        }
                        item = item.getParent() instanceof View ? (View)item.getParent() : null;
                    }
                }
            });
            if (!clicked[0]) SystemClock.sleep(40);
        }
        assertTrue("Missing dialog action " + label, clicked[0]);
    }
    private static View find(View view,String label){return find(view,label,View.class);}
    private static View find(View view,String label,Class<?> type){
        if (type.isInstance(view) && (label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())
                || view instanceof TextView && label.contentEquals(((TextView)view).getText())))return view;
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),label,type);if(found!=null)return found;}
        }
        return null;
    }

    /** Local HTTP server exercises production requests and Android foreground-service delivery. */
    private static final class Fixture implements Closeable {
        final ServerSocket server;
        final AtomicInteger posts=new AtomicInteger();
        final CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1);
        volatile boolean videoReady;
        final Thread thread;
        Fixture() throws Exception {
            server=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));
            thread=new Thread(()->{
                while(!server.isClosed()) {
                    try(Socket socket=server.accept()){reply(socket);}
                    catch(Exception ignored){if(server.isClosed())return;}
                }
            },"media-fixture");
            thread.start();
        }
        private void reply(Socket socket) throws Exception {
            socket.setSoTimeout(7000);
            BufferedInputStream input=new BufferedInputStream(socket.getInputStream());
            String request=line(input);
            int length=0;String field;
            while(!(field=line(input)).isEmpty()){
                if(field.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
                    length=Integer.parseInt(field.substring(field.indexOf(':')+1).trim());
            }
            for(int i=0;i<length;i++)if(input.read()<0)throw new java.io.EOFException();
            int code=200;String type="application/json";byte[] bytes;
            if(request.startsWith("POST /v1/images/generations ")) {
                int attempt=posts.incrementAndGet();received.countDown();
                release.await(15,TimeUnit.SECONDS);
                if(attempt==1){code=400;bytes=utf("{\"error\":{\"message\":\"fixture failure\"}}");}
                else bytes=utf("{\"data\":[{\"b64_json\":\""+png()+"\"}]}");
            } else if(request.startsWith("POST /v1/videos ")) {
                posts.incrementAndGet();bytes=utf("{\"id\":\"job01\",\"status\":\"queued\"}");
            } else if(request.startsWith("GET /v1/videos/job01/content ")) {
                type="video/mp4";bytes=new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m'};
            } else if(request.startsWith("GET /v1/videos/job01 ")) {
                bytes=utf("{\"id\":\"job01\",\"status\":\""+(videoReady?"completed":"in_progress")+"\"}");
            } else {code=404;bytes=utf("{}");}
            BufferedOutputStream output=new BufferedOutputStream(socket.getOutputStream());
            output.write(utf("HTTP/1.1 "+code+(code==200?" OK":" Error")+"\r\nContent-Type: "+type+"\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n"));
            output.write(bytes);output.flush();
        }
        private static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
        private static String line(BufferedInputStream input)throws Exception{
            ByteArrayOutputStream out=new ByteArrayOutputStream();int b;
            while((b=input.read())!=-1 && b!=10){if(b!=13)out.write(b);}
            if(b<0)throw new java.io.EOFException();
            return out.toString("UTF-8");
        }
        @Override public void close()throws java.io.IOException {release.countDown();server.close();}
    }
}
