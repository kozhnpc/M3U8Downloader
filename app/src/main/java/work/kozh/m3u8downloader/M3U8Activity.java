package work.kozh.m3u8downloader;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import work.kozh.m3u8.download.M3u8DownloadFactory;
import work.kozh.m3u8.listener.Listener;
import work.kozh.m3u8.utils.Constant;

public class M3U8Activity extends AppCompatActivity {

    private ProgressBar mProgressBar;
    private TextView mPercent;
    private TextView mSpeed;
    private TextView mNum;
    private Button mButton11;
    private EditText mEditText;
    private EditText mFileName;

    private static final String M3U8URL = "https://dm-h.phncdn.com/hls/videos/201908/30/245040181/,720P_4000K,480P_2000K,240P_400K,_245040181.mp4" +
            ".urlset/master.m3u8?ttl=1586147924&l=0&hash=de19bf8db631464a5907e2e51b2411d1";

    private M3u8DownloadFactory.M3u8Download mDownload;

    private final int DOWNLOAD = 0;
    private final int DOWNLOADING = 1;
    private final int ERROR = 2;
    private final int END = 3;
    private final int SPEED = 4;
    private final int INFO = 5;

    @SuppressLint("HandlerLeak")
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case DOWNLOAD:
                    Toast.makeText(M3U8Activity.this, "开始下载文件...", Toast.LENGTH_LONG).show();
                    mProgressBar.setVisibility(View.VISIBLE);
                    break;
                case DOWNLOADING:
                    M3u8DownloadFactory.M3U8DownloadInfo info = (M3u8DownloadFactory.M3U8DownloadInfo) msg.obj;
                    mProgressBar.setProgress((int) info.percent);
                    mPercent.setText(info.percent + "%");
                    mNum.setText(info.finished + " / " + info.sum + " " + info.downloadSize);
                    break;
                case ERROR:
                    M3u8DownloadFactory.M3U8DownloadInfo error = (M3u8DownloadFactory.M3U8DownloadInfo) msg.obj;
                    Toast.makeText(M3U8Activity.this, "下载出错：" + error.error, Toast.LENGTH_LONG).show();
                    mButton11.setEnabled(true);
                    break;
                case END:
                    mProgressBar.setProgress(100);
//                    Toast.makeText(M3U8Activity.this, "下载完成", Toast.LENGTH_LONG).show();
                    mButton11.setEnabled(true);
                    break;
                case INFO:
                    //显示下载过程中的信息提示
                    M3u8DownloadFactory.M3U8DownloadInfo info3 = (M3u8DownloadFactory.M3U8DownloadInfo) msg.obj;
                    Toast.makeText(M3U8Activity.this, info3.info, Toast.LENGTH_LONG).show();
                    break;
                case SPEED:
                    M3u8DownloadFactory.M3U8DownloadInfo info1 = (M3u8DownloadFactory.M3U8DownloadInfo) msg.obj;
                    mSpeed.setText(info1.speed);
                    break;
            }

            super.handleMessage(msg);
        }
    };

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mProgressBar = findViewById(R.id.progressBar);
        mPercent = findViewById(R.id.percent);
        mSpeed = findViewById(R.id.speed);
        mNum = findViewById(R.id.num);
        mButton11 = findViewById(R.id.button11);
        mEditText = findViewById(R.id.editText);
        mFileName = findViewById(R.id.fileName);

        mProgressBar.setMax(100);

    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public void download(View view) {

        String url = mEditText.getText().toString();

        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, "请输入地址", Toast.LENGTH_SHORT).show();
            return;
        }

        //        mDownload = M3u8DownloadFactory.getInstance(M3U8URL);
        mDownload = M3u8DownloadFactory.getInstance(url);
        //设置生成目录
        String path = Environment.getExternalStorageDirectory().getAbsolutePath() + "/m3u8/";
        mDownload.setDir(path);

        //设置视频名称
        String name = mFileName.getText().toString();
        if (TextUtils.isEmpty(name)) {
            Date day = new Date();
            @SuppressLint("SimpleDateFormat") SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
            mDownload.setFileName(df.format(day));
        } else {
            mDownload.setFileName(name);
        }

        //设置线程数
        mDownload.setThreadCount(30);
        //设置重试次数
        mDownload.setRetryCount(30);
        //设置连接超时时间（单位：毫秒）
        mDownload.setTimeoutMillisecond(10000L);
        /*
        设置日志级别
        可选值：NONE INFO DEBUG ERROR
        */
        mDownload.setLogLevel(Constant.INFO);
        //设置监听器间隔（单位：毫秒）
        mDownload.setInterval(500L);

        //添加额外请求头
      /*  Map<String, Object> headersMap = new HashMap<>();
        headersMap.put("Content-Type", "text/html;charset=utf-8");
        m3u8Download.addRequestHeaderMap(headersMap);*/

        //添加监听器
        mDownload.addListener(new Listener() {
            @Override
            public void start() {
//                System.out.println("开始下载！");
                mHandler.sendEmptyMessage(DOWNLOAD);
            }

            @Override
            public void process(String downloadUrl, final int finished, final int sum, String downloadSize, final float percent) {
//                System.out.println("下载网址：" + downloadUrl + "\t已下载" + finished + "个\t一共" + sum + "个\t已完成" + percent + "%");
                Message message = Message.obtain();
                message.what = DOWNLOADING;
                M3u8DownloadFactory.M3U8DownloadInfo info = new M3u8DownloadFactory.M3U8DownloadInfo((int) percent, sum, finished, downloadSize);
                message.obj = info;
                mHandler.sendMessage(message);
            }

            @Override
            public void speed(final String speedPerSecond) {
//                System.out.println("下载速度：" + speedPerSecond);
                Message message = Message.obtain();
                message.what = SPEED;
                M3u8DownloadFactory.M3U8DownloadInfo info = new M3u8DownloadFactory.M3U8DownloadInfo();
                info.speed = speedPerSecond;
                message.obj = info;
                mHandler.sendMessage(message);

            }

            @Override
            public void error(String msg) {
                Message message = Message.obtain();
                message.what = ERROR;
                M3u8DownloadFactory.M3U8DownloadInfo info = new M3u8DownloadFactory.M3U8DownloadInfo();
                info.error = msg;
                message.obj = info;
                mHandler.sendMessage(message);

            }

            @Override
            public void info(String msg) {
                Message message = Message.obtain();
                message.what = INFO;
                M3u8DownloadFactory.M3U8DownloadInfo info = new M3u8DownloadFactory.M3U8DownloadInfo();
                info.info = msg;
                message.obj = info;
                mHandler.sendMessage(message);
            }

            @Override
            public void end() {
                mHandler.sendEmptyMessage(END);
//                System.out.println("下载完毕");
            }
        });

        mButton11.setEnabled(false);
//        Toast.makeText(M3U8Activity.this, "开始获取视频信息，请稍等...", Toast.LENGTH_LONG).show();
        mDownload.start();
    }

    public void pause(View view) {
        Toast.makeText(this, "是否正在下载中：" + M3u8DownloadFactory.isDownloading(), Toast.LENGTH_LONG).show();
    }

    public void end(View view) {


    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

}
