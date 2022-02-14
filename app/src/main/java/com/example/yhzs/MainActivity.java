package com.example.yhzs;

import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import org.keplerproject.luajava.JavaFunction;
import org.keplerproject.luajava.LuaException;
import org.keplerproject.luajava.LuaState;
import org.keplerproject.luajava.LuaStateFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    public void copyAssets(Context context, String oldPath, String newPath) {  //assets路径
        try {
            String[] fileNames = context.getAssets().list(oldPath);
            if (fileNames.length > 0) {
                File file = new File(newPath);
                file.mkdirs();
                for (String fileName : fileNames) {
                    copyAssets(context, oldPath + "/" + fileName, newPath + "/" + fileName);
                }
            } else {
                InputStream is = context.getAssets().open(oldPath);
                FileOutputStream fos = new FileOutputStream(new File(newPath));
                byte[] buffer = new byte[1024];
                int byteCount = 0;
                while ((byteCount = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, byteCount);
                }
                fos.flush();
                is.close();
                fos.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ------------------悬浮窗 start--------------------------------
    public static boolean param_bool = false; // 悬浮窗只开启一次
    public static boolean isLua_Running = false; // Lua运行状态
    public static boolean isLua_Err = false;   //用户操作状态

    private WindowManager windowManager; //屏幕全局窗口
    private WindowManager.LayoutParams layoutParams; //悬浮窗参数
    private Button floatView;   //悬浮窗

    // 点击开启悬浮窗口
    public void startFloatingService(View view) { //点击响应函数  现授权,授权成功显示悬浮窗
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "当前无权限，请授权", Toast.LENGTH_SHORT);
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), 0);
        } else {
            if (!param_bool) {
                param_bool = true;
                //startService(new Intent(MainActivity.this, FloatingService.class));
                showFloatView();
            }
        }
    }

    @SuppressLint("AppCompatCustomView")
    public class FloatView extends Button {

        public FloatView(Context context) {
            super(context);
            this.setText("运行");
            this.setBackgroundColor(Color.argb(125, 0, 0, 0));//背景
            this.setTextSize(10);
            this.setTextColor(Color.argb(255, 255, 255, 255));


            this.setOnTouchListener(new View.OnTouchListener() { //监听子视图按钮 内部移动
                private int x;
                private int y;

                @Override
                public boolean onTouch(View view, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            x = (int) event.getRawX();
                            y = (int) event.getRawY();
                            break;
                        case MotionEvent.ACTION_MOVE:
                            int nowX = (int) event.getRawX();
                            int nowY = (int) event.getRawY();
                            int movedX = nowX - x;
                            int movedY = nowY - y;
                            x = nowX;
                            y = nowY;
                            layoutParams.x = layoutParams.x + movedX;
                            layoutParams.y = layoutParams.y + movedY;

                            // 更新悬浮窗控件布局
                            windowManager.updateViewLayout(view, layoutParams);
                            break;
                        default:
                            break;
                    }
                    return false;
                }
            });

            this.setOnClickListener(new View.OnClickListener() { // 监听按钮点击操作
                @Override
                public void onClick(View view) {
                    if (!isLua_Running) {
                        isLua_Running = true;
                        Toast.makeText(getApplicationContext(), "Lua开始运行", Toast.LENGTH_SHORT).show();
                        new Thread() { //子线程
                            @Override
                            public void run() {
                                mHandler.sendEmptyMessage(1);//子线程对主线程(UI线程) 通知代号:1
                                LuaState lua = LuaStateFactory.newLuaState(); //创建栈
                                lua.openLibs(); //加载标准库

                                // 注册为 Lua 全局函数
                                try {
                                    new Java_print(lua).register("print"); // 注册print
                                    new Java_sleep(lua).register("sleep"); // 注册sleep
                                    new Java_touchDown(lua).register("touchDown"); // 注册sleep
                                    new Java_touchUp(lua).register("touchUp"); // 注册sleep
                                } catch (LuaException e) {
                                    e.printStackTrace();
                                }
                                String filePath = getExternalFilesDir(null).getAbsolutePath(); //获取手机里
                                copyAssets(getApplicationContext(), "luasrc", filePath);  //拷贝目录到手机里
                                int err = lua.LdoFile(filePath + "/main.lua");
                                if (err == 1) {
                                    String str = lua.toString(-1);
                                    if (str.equals("lua_err")) {
                                        Log.e("Lua", "用户点击了停止");
                                    } else {
                                        Log.e("Lua", "报错信息 err" + str);
                                    }
                                }
                                //Log.e("Lua", "测试");
                                mHandler.sendEmptyMessage(0);//子线程对主线程(UI线程) 通知代号:0
                                lua.close(); //养成良好习惯，在执行完毕后销毁Lua栈。

                                isLua_Running = false;
                                isLua_Err = false;
                            }
                        }.start();

                    } else {
                        isLua_Err = true; //停止
                    }
                }
            });

        }
    }

    public void showFloatView() { // 悬浮窗初始化
        // 获取WindowManager服务
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        layoutParams = new WindowManager.LayoutParams();

        // 设置LayoutParam
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutParams.type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        layoutParams.format = PixelFormat.RGBA_8888;
        layoutParams.gravity = Gravity.LEFT | Gravity.TOP;
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; //悬浮窗遮挡,并且监听用户点击位置
        layoutParams.width = 100;
        layoutParams.height = 100;
        layoutParams.x = 0;
        layoutParams.y = 0;
        floatView = new FloatView(getApplicationContext());
        // 将悬浮窗控件添加到WindowManager
        windowManager.addView(floatView, layoutParams);

        thread.start();
    }

    private Handler mHandler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case 0:
                    floatView.setText("运行");
                    break;
                case 1:
                    floatView.setText("暂停");
                    break;
                case 2:
                    break;
                default:
            }
            return false;
        }
    });
    // ------------------悬浮窗 end--------------------------------

    // ------------------lua函数 start--------------------------------
    class Java_print extends JavaFunction {
        public Java_print(LuaState luaState) {
            super(luaState);
        }

        @Override
        public int execute() throws LuaException {
            if (isLua_Err) {
                L.pushString("lua_err");
                L.error();
            }
            // 获取Lua传入的参数，注意第一个参数固定为上下文环境。
            String str = L.toString(2);
            Log.i("Lua", str);
            return 1; // 返回值的个数
        }
    }

    class Java_sleep extends JavaFunction {
        public Java_sleep(LuaState luaState) {
            super(luaState);
        }

        @Override
        public int execute() {
            if (isLua_Err) {
                L.pushString("lua_err");
                L.error();
            }

            // 获取Lua传入的参数，注意第一个参数固定为上下文环境。
            SystemClock.sleep((long) L.toNumber(2));
            return 0; // 返回值的个数
        }
    }

    public static final int MSG_MOTIONEVENT = 0;
    final AuxiliaryThread thread = new AuxiliaryThread();

    //用子线程来发送采集到的MotionEvent事件
    class AuxiliaryThread extends Thread {
        public Handler tHandler;
        private Instrumentation mInst = new Instrumentation();
        @Override
        public void run() {
            Looper.prepare(); // 始化一个looper保存到当前线程中
            tHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case MSG_MOTIONEVENT:
                            Bundle bundle = msg.getData();
                            float rx = bundle.getFloat("x");
                            float ry = bundle.getFloat("y");

                            mInst.sendPointerSync(MotionEvent.obtain(SystemClock.uptimeMillis(),
                                    SystemClock.uptimeMillis(), msg.arg1,
                                    rx, ry, 0));
                            break;
                    }
                }
            };
            Looper.loop();
        }
    }

    class Java_touchDown extends JavaFunction {

        public Java_touchDown(LuaState luaState) {
            super(luaState);
        }
        @Override
        public int execute() {
            if (isLua_Err) {
                L.pushString("lua_err");
                L.error();
            }
            double index = L.toNumber(2);
            float x = (float) L.toNumber(3);
            float y = (float) L.toNumber(4);

            final Message msg = Message.obtain();
            msg.what = MSG_MOTIONEVENT;
            msg.arg1 = MotionEvent.ACTION_DOWN;
            Bundle bundle = new Bundle();
            bundle.putFloat("x", x);
            bundle.putFloat("y", y);
            msg.setData(bundle);
            thread.tHandler.sendMessageDelayed(msg, 2000);
            Log.d("按下位置", x + "," + y);
            return 0; // 返回值的个数
        }
    }
    class Java_touchUp extends JavaFunction {
        public Java_touchUp(LuaState luaState) {
            super(luaState);
        }

        @Override
        public int execute() {
            if (isLua_Err) {
                L.pushString("lua_err");
                L.error();
            }
            double index = L.toNumber(2);
            float x = (float) L.toNumber(3);
            float y = (float) L.toNumber(4);

            final Message msg = Message.obtain();
            msg.what = MSG_MOTIONEVENT;
            msg.arg1 = MotionEvent.ACTION_UP;
            Bundle bundle = new Bundle();
            bundle.putFloat("x", x);
            bundle.putFloat("y", y);
            msg.setData(bundle);
            thread.tHandler.sendMessageDelayed(msg, 2000);
            Log.d("抬起位置", x + "," + y);

            return 0; // 返回值的个数
        }
    }
    // ------------------lua函数 end--------------------------------
}