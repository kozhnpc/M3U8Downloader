package work.kozh.m3u8.download;

import android.annotation.SuppressLint;
import android.os.Build;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Security;
import java.security.spec.AlgorithmParameterSpec;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.ToIntFunction;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import androidx.annotation.RequiresApi;
import work.kozh.m3u8.exception.M3u8Exception;
import work.kozh.m3u8.listener.Listener;
import work.kozh.m3u8.utils.Constant;
import work.kozh.m3u8.utils.LogUtil;
import work.kozh.m3u8.utils.MediaFormat;
import work.kozh.m3u8.utils.StringUtils;

/**
 * M3U8视频下载器
 * <p>
 * 支持ts文件加密处理
 * <p>
 * 对于多线程下载 已经进行了内存优化
 * <p>
 * 加入了各类下载信息提示
 * <p>
 * 每次只能下载一个文件  多线程下载单个m3u8文件
 *
 */

public class M3u8DownloadFactory {

    private static String mTempDir;
    private static M3u8Download m3u8Download;
    //是否正在下载中
    private static boolean sIsDownloading = false;

    /**
     *
     * 解决java不支持AES/CBC/PKCS7Padding模式解密
     *
     */
    static {
        Security.addProvider(new BouncyCastleProvider());
    }


    @RequiresApi(api = Build.VERSION_CODES.N)
    public static class M3u8Download {

        //要下载的m3u8链接
        private final String DOWNLOADURL;

        //优化内存占用
        private static final BlockingQueue<byte[]> BLOCKING_QUEUE = new LinkedBlockingQueue<>();

        //线程数 默认30个
        private int threadCount = 30;

        //重试次数 默认30次
        private int retryCount = 30;

        //链接连接超时时间（单位：毫秒）
        private long timeoutMillisecond = 1000L;

        //合并后的文件存储目录
        private String dir;

        //合并后的视频文件名称
        private String fileName;

        //已完成ts片段个数
        private int finishedCount = 0;

        //解密算法名称
        private String method;

        //密钥
        private String key = "";

        //密钥字节
        private byte[] keyBytes = new byte[16];

        //key是否为字节
        private boolean isByte = false;

        //IV
        private String iv = "";

        //所有ts片段下载链接
        private Set<String> tsSet = new LinkedHashSet<>();

        //解密后的片段
        private Set<File> finishedFiles =
                new ConcurrentSkipListSet<>(Comparator.comparingInt(new ToIntFunction<File>() {
                    @Override
                    public int applyAsInt(File o) {
                        return Integer.parseInt(o.getName().replace(".xyz", ""));
                    }
                }));

        //已经下载的文件大小
        private BigDecimal downloadBytes = new BigDecimal(0);

        //监听间隔 默认0.5秒
        private volatile long interval = 500L;

        //自定义请求头
        private Map<String, Object> requestHeaderMap = new HashMap<>();
        ;

        //监听事件
        private Set<Listener> listenerSet = new HashSet<>(5);

        /**
         * 开始下载视频
         */
        public void start() {
            ExecutorService executorService = Executors.newCachedThreadPool();
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        //设置下载线程数 默认30
                        setThreadCount(30);
                        //校验文件完整
                        checkField();
                        //读取m3u8文件 涉及到联网获取
                        String tsUrl = getTsUrl();
                        //判断是否需要解密ts文件
                        if (StringUtils.isEmpty(tsUrl)) {
                            LogUtil.i("不需要解密");
                        } else {
                            LogUtil.i("文件需要解密，开始解密...");
                            for (Listener downloadListener : listenerSet) {
                                downloadListener.info("文件需要解密，开始解密...");
                            }
                        }
                        //准备完毕，开始下载
                        startDownload();
                    } catch (Exception e) {
                        e.printStackTrace();
                        LogUtil.i("错误信息收集：" + e.getMessage());
                        for (Listener downloadListener : listenerSet) {
                            downloadListener.error(e.getMessage());
                        }
                        sIsDownloading = false;
                        destroied();
                        e.printStackTrace();
                    }

                }
            });

        }


        /**
         * 下载视频
         */
        private void startDownload() {
            //线程池
            final ExecutorService fixedThreadPool = Executors.newFixedThreadPool(threadCount);
            int i = 0;
            //如果生成目录不存在，则创建
            File file1 = new File(dir);
            if (!file1.exists())
                file1.mkdirs();
            //执行多线程下载
            for (String s : tsSet) {
                i++;
                fixedThreadPool.execute(getDownloadThread(s, i));
            }
            fixedThreadPool.shutdown();

            //下载过程监视  开启一个子线程专门用于监视下载过程变化
            new Thread(new Runnable() {
                @Override
                public void run() {
                    int consume = 0;
                    //轮询是否下载成功
                    while (!fixedThreadPool.isTerminated()) {
                        try {
                            consume++;
                            BigDecimal bigDecimal = new BigDecimal(downloadBytes.toString());
                            LogUtil.i("已下载的大小：" + StringUtils.convertToDownloadSpeed(downloadBytes, 2));
                            Thread.sleep(1000L);

                            /*for (Listener downloadListener : listenerSet) {
                                downloadListener.info("已用时" + consume + "秒！");
                            }*/

                            LogUtil.i("已用时" + consume + "秒！\t下载速度：" + StringUtils.convertToDownloadSpeed(new BigDecimal(downloadBytes.toString()).subtract(bigDecimal), 3) + "/s");
                            LogUtil.i("\t已完成" + finishedCount + "个，还剩" + (tsSet.size() - finishedCount) + "个！");
                            LogUtil.i(new BigDecimal(finishedCount).divide(new BigDecimal(tsSet.size()), 4, BigDecimal.ROUND_HALF_UP).multiply(new BigDecimal(100)).setScale(2, BigDecimal.ROUND_HALF_UP) + "%");
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    LogUtil.i("下载完成，正在合并文件！共" + finishedFiles.size() + "个！" + StringUtils.convertToDownloadSpeed(downloadBytes, 3));
                    /*for (Listener downloadListener : listenerSet) {
                        downloadListener.info("下载完成，正在合并文件！");
                    }*/
                    //开始合并视频
                    M3u8Download.this.mergeTs();
                    //删除多余的ts片段
                    M3u8Download.this.deleteFiles();
                    LogUtil.i("视频合并完成，欢迎使用!");
                }
            }).start();
            //开启多个子线程 监听下载过程的进度和速度
            startListener(fixedThreadPool);
        }

        //监听回调方法
        private void startListener(ExecutorService fixedThreadPool) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    for (Listener downloadListener : listenerSet)
                        downloadListener.start();
                    sIsDownloading = true;
                    //轮询是否下载成功
                    while (!fixedThreadPool.isTerminated()) {
                        //监听下载进度
                        try {
                            Thread.sleep(interval);
                            for (Listener downloadListener : listenerSet)
                                downloadListener.process(DOWNLOADURL,
                                        finishedCount,
                                        tsSet.size(),
                                        StringUtils.convertToDownloadSpeed(downloadBytes, 2),
                                        new BigDecimal(finishedCount).divide(new BigDecimal(tsSet.size()), 4, BigDecimal.ROUND_HALF_UP).multiply(new BigDecimal(100)).setScale(2, BigDecimal.ROUND_HALF_UP).floatValue());
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    for (Listener downloadListener : listenerSet) {
                        downloadListener.end();
                    }

                    //下载结束后 销毁下载管理对象
                    destroied();
                    sIsDownloading = false;

                }
            }).start();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!fixedThreadPool.isTerminated()) {
                        //监听下载速度变化
                        try {
                            BigDecimal bigDecimal = new BigDecimal(downloadBytes.toString());
                            Thread.sleep(1000L);
                            for (Listener downloadListener : listenerSet)
                                downloadListener.speed(StringUtils.convertToDownloadSpeed(new BigDecimal(downloadBytes.toString()).subtract(bigDecimal)
                                        , 3) + "/s");
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }).start();
        }

        /**
         * 合并下载好的ts片段
         */
        private void mergeTs() {
            try {
                File file = new File(dir + Constant.FILESEPARATOR + fileName + ".mp4");
                System.gc();
                if (file.exists())
                    file.delete();
                else file.createNewFile();
                FileOutputStream fileOutputStream = new FileOutputStream(file);
                byte[] b = new byte[4096];
                for (File f : finishedFiles) {
                    FileInputStream fileInputStream = new FileInputStream(f);
                    int len;
                    while ((len = fileInputStream.read(b)) != -1) {
                        fileOutputStream.write(b, 0, len);
                    }
                    fileInputStream.close();
                    fileOutputStream.flush();
                }
                fileOutputStream.close();

                for (Listener downloadListener : listenerSet) {
                    downloadListener.info("下载完成，文件保存在 " + file.getAbsolutePath());
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        /**
         * 删除下载好的片段
         */
        private void deleteFiles() {
            File file = new File(dir + Constant.FILESEPARATOR + mTempDir);
            for (File f : file.listFiles()) {
                if (f.getName().endsWith(".xy") || f.getName().endsWith(".xyz"))
                    f.delete();
            }
            //最后将文件夹删除
            file.delete();
        }

        /**
         * 开启下载线程  开启下载任务线程
         * <p>
         * 多线程下载多文件 每个文件一个线程
         * <p>
         * 以.ts文件为单位，开启一个线程下载 从线程池中获取线程
         * <p>
         * 需要加上当前时间作为文件夹
         * （由于合并时是根据文件夹来合并的，合并之后需要删除所有的ts文件，这里用到了多线程，所以需要按文件夹来存ts）
         *
         * @param urls ts片段链接
         * @param i    ts片段序号
         * @return 线程
         */
        private Thread getDownloadThread(String urls, int i) {

            return new Thread(new Runnable() {
                @Override
                public void run() {
//                    LogUtil.i("下载的ts文件url：" + urls);
                    int count = 1;
                    HttpURLConnection httpURLConnection = null;
                    //xy为未解密的ts片段，如果存在，则删除  重新下载
                    //临时下载路径需要加上这个一个文件夹，当作每个视频的辨识
                    File file2 = new File(dir + mTempDir + Constant.FILESEPARATOR + i + ".xy");
//                    File file2 = new File(dir + Constant.FILESEPARATOR + urls + ".xy");
                    if (file2.exists()) {
                        file2.delete();
                    } else {
                        try {
                            //先得到文件的上级目录，并创建上级目录，在创建文件
                            file2.getParentFile().mkdir();
                            file2.createNewFile();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    OutputStream outputStream = null;
                    InputStream inputStream1 = null;
                    FileOutputStream outputStream1 = null;
                    byte[] bytes;
                    try {
                        //优化 使用缓冲池
                        bytes = BLOCKING_QUEUE.take();
                    } catch (InterruptedException e) {
                        bytes = new byte[Constant.BYTE_COUNT];
                    }
                    //重试次数判断
                    while (count <= retryCount) {
                        try {
                            //模拟http请求获取ts片段文件
                            URL url = new URL(urls);
                            httpURLConnection = (HttpURLConnection) url.openConnection();
                            httpURLConnection.setConnectTimeout((int) timeoutMillisecond);
                            for (Map.Entry<String, Object> entry : requestHeaderMap.entrySet())
                                httpURLConnection.addRequestProperty(entry.getKey(), entry.getValue().toString());
                            httpURLConnection.setUseCaches(false);
                            httpURLConnection.setReadTimeout((int) timeoutMillisecond);
                            httpURLConnection.setDoInput(true);
                            InputStream inputStream = httpURLConnection.getInputStream();
                            try {
                                outputStream = new FileOutputStream(file2);
                            } catch (FileNotFoundException e) {
                                e.printStackTrace();
                                continue;
                            }
                            int len;
                            //将未解密的ts片段写入文件
                            while ((len = inputStream.read(bytes)) != -1) {
                                outputStream.write(bytes, 0, len);
                                synchronized (M3u8Download.this) {
                                    downloadBytes = downloadBytes.add(new BigDecimal(len));
                                }
                            }
                            outputStream.flush();
                            inputStream.close();

                            inputStream1 = new FileInputStream(file2);
                            int available = inputStream1.available();
                            if (bytes.length < available)
                                bytes = new byte[available];
                            inputStream1.read(bytes);

                            File file = new File(dir + mTempDir + Constant.FILESEPARATOR + i + ".xyz");

//                            File file = new File(dir + Constant.FILESEPARATOR + urls + ".xyz");
                            outputStream1 = new FileOutputStream(file);
                            //开始解密ts片段，这里我们把ts后缀改为了xyz，改不改都一样
                            byte[] decrypt = M3u8Download.this.decrypt(bytes, available, key, iv, method);
                            if (decrypt == null)
                                outputStream1.write(bytes, 0, available);
                            else outputStream1.write(decrypt);
                            finishedFiles.add(file);
                            break;
                        } catch (Exception e) {
                            if (e instanceof InvalidKeyException || e instanceof InvalidAlgorithmParameterException) {
                                for (Listener downloadListener : listenerSet) {
                                    downloadListener.error("解密文件失败！");
                                }
                                sIsDownloading = false;
                                destroied();
                                LogUtil.e("解密失败！");
                                break;
                            }
                            for (Listener downloadListener : listenerSet) {
                                downloadListener.info("重试中...第" + count + "次获取链接！");
                            }
                            LogUtil.d("第" + count + "获取链接重试！\t" + urls);
                            count++;
//                        e.printStackTrace();
                        } finally {
                            try {
                                if (inputStream1 != null)
                                    inputStream1.close();
                                if (outputStream1 != null)
                                    outputStream1.close();
                                if (outputStream != null)
                                    outputStream.close();
                                BLOCKING_QUEUE.put(bytes);
                            } catch (IOException | InterruptedException e) {
                                e.printStackTrace();
                            }
                            if (httpURLConnection != null) {
                                httpURLConnection.disconnect();
                            }
                        }
                    }
                    if (count > retryCount) {
                        //自定义异常
//                        throw new M3u8Exception("连接超时！");
                        for (Listener downloadListener : listenerSet) {
                            downloadListener.error("连接超时！");
                        }
                        throw new M3u8Exception("连接超时");
                    }
                    finishedCount++;
//                LogUtil.i(urls + "下载完毕！\t已完成" + finishedCount + "个，还剩" + (tsSet.size() - finishedCount) + "个！");
                }
            });
        }

        /**
         * 获取所有的ts片段下载链接
         * <p>
         * 开始解析m3u8文件
         *
         * @return 链接是否被加密，null为非加密
         */
        private String getTsUrl() {
            //通知外部，正在获取m3u8信息
            for (Listener downloadListener : listenerSet) {
                downloadListener.info("正在获取视频信息...");
            }
            StringBuilder content = getUrlContent(DOWNLOADURL, false);
            //判断是否是m3u8链接
            if (!content.toString().contains("#EXTM3U")) {
                for (Listener downloadListener : listenerSet) {
                    downloadListener.error(DOWNLOADURL + "不是m3u8链接！");
                }
                throw new M3u8Exception(DOWNLOADURL + "不是m3u8链接！");
            }
            //按照换行进行筛选
            String[] split = content.toString().split("\\n");
            String keyUrl = "";
            boolean isKey = false;
            for (String s : split) {
                //如果含有此字段，则说明只有一层m3u8链接
                if (s.contains("#EXT-X-KEY") || s.contains("#EXTINF")) {
                    isKey = true;
                    keyUrl = DOWNLOADURL;
                    break;
                }
                //如果含有此字段，则说明ts片段链接需要从第二个m3u8链接获取
                //有可能有多个m3u8文件，对应不同的分辨率 默认处理第一个
                // 这里做了判断，即便有多个m3u8文件，只读取第一个文件，后续的不管了
                if (s.contains(".m3u8")) {
                    if (StringUtils.isUrl(s))
                        return s;
                    String relativeUrl = DOWNLOADURL.substring(0, DOWNLOADURL.lastIndexOf("/") + 1);
                    if (s.startsWith("/"))
                        s = s.replaceFirst("/", "");
                    keyUrl = mergeUrl(relativeUrl, s);
                    LogUtil.i("读取到的第一个文件流地址：\n" + keyUrl);
//                    LogUtil.i("测试文件流地址：\n" + keyUrl);
                    break;
                }
            }
            if (StringUtils.isEmpty(keyUrl)) {
                for (Listener downloadListener : listenerSet) {
                    downloadListener.error("解密视频文件出错：未发现key链接！");
                }
                throw new M3u8Exception("未发现key链接！");
            }
            //获取密钥
            String key1 = isKey ? getKey(keyUrl, content) : getKey(keyUrl, null);
            if (StringUtils.isNotEmpty(key1))
                key = key1;
            else key = null;
            return key;
        }

        /**
         * 根据m3u8文本 获取到真正下载的ts文件的下载地址，并保存到集合中
         * 获取ts解密的密钥，并把ts片段加入set集合
         * 返回的是加密的密文
         *
         * @param url     密钥链接，如果无密钥的m3u8，则此字段可为空
         * @param content 内容，如果有密钥，则此字段可以为空
         * @return ts是否需要解密，null为不解密
         */
        private String getKey(String url, StringBuilder content) {

            StringBuilder urlContent;
            if (content == null || StringUtils.isEmpty(content.toString()))
                urlContent = getUrlContent(url, false);
            else urlContent = content;
            if (!urlContent.toString().contains("#EXTM3U")) {
                for (Listener downloadListener : listenerSet) {
                    downloadListener.error(DOWNLOADURL + "不是m3u8链接！");
                }
                throw new M3u8Exception(DOWNLOADURL + "不是m3u8链接！");
            }
            //按行解析地址
            String[] split = urlContent.toString().split("\\n");

            //循环第一遍 判断是否加密
            for (String s : split) {

                //如果含有此字段，则获取加密算法以及获取密钥的链接
                if (s.contains("EXT-X-KEY")) {
                    String[] split1 = s.split(",");
                    for (String s1 : split1) {
                        if (s1.contains("METHOD")) {
                            method = s1.split("=", 2)[1];
                            continue;
                        }
                        if (s1.contains("URI")) {
                            key = s1.split("=", 2)[1];
                            continue;
                        }
                        if (s1.contains("IV"))
                            iv = s1.split("=", 2)[1];
                    }
                }
            }

            //循环第二遍 拼接ts文件的地址
            String relativeUrl = url.substring(0, url.lastIndexOf("/") + 1);
            //将ts片段链接加入set集合
            for (int i = 0; i < split.length; i++) {
                String s = split[i];
                if (s.contains("#EXTINF")) {
                    String s1 = split[++i];
                    tsSet.add(StringUtils.isUrl(s1) ? s1 : mergeUrl(relativeUrl, s1));
                }
            }

            //最终的结果 如果加密了
            if (!StringUtils.isEmpty(key)) {
                key = key.replace("\"", "");
                return getUrlContent(StringUtils.isUrl(key) ? key : relativeUrl + key, true).toString().replaceAll("\\s+", "");
            }

            return null;
        }

        /**
         * 模拟http请求获取内容
         * <p>
         * 传入一个m3u8地址，解析其中的文本内容
         *
         * @param urls  http链接
         * @param isKey 这个url链接是否用于获取key
         * @return 内容
         */
        private StringBuilder getUrlContent(String urls, boolean isKey) {

            int count = 1;
            HttpURLConnection httpURLConnection = null;
            StringBuilder content = new StringBuilder();
            while (count <= retryCount) {
                try {
                    URL url = new URL(urls);
                    httpURLConnection = (HttpURLConnection) url.openConnection();
                    httpURLConnection.setConnectTimeout((int) timeoutMillisecond);
                    httpURLConnection.setReadTimeout((int) timeoutMillisecond);
                    httpURLConnection.setUseCaches(false);
                    httpURLConnection.setDoInput(true);
                    for (Map.Entry<String, Object> entry : requestHeaderMap.entrySet())
                        httpURLConnection.addRequestProperty(entry.getKey(), entry.getValue().toString());
                    String line;
                    InputStream inputStream = httpURLConnection.getInputStream();
                    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
                    if (isKey) {
                        byte[] bytes = new byte[128];
                        int len;
                        len = inputStream.read(bytes);
                        isByte = true;
                        if (len == 1 << 4) {
                            keyBytes = Arrays.copyOf(bytes, 16);
                            content.append("isByte");
                        } else
                            content.append(new String(Arrays.copyOf(bytes, len)));
                        return content;
                    }
                    while ((line = bufferedReader.readLine()) != null)
                        content.append(line).append("\n");
                    bufferedReader.close();
                    inputStream.close();

                    //打印获取到的m3u8文本内容
                    LogUtil.i("正在解析一个m3u8文件的内容：" + content);

                    break;
                } catch (Exception e) {
                    LogUtil.d("第" + count + "获取链接重试！\t" + urls);
                    for (Listener downloadListener : listenerSet) {
                        downloadListener.info("重试中...第" + count + "次获取链接！");
                    }
                    count++;
                    e.printStackTrace();

                } finally {
                    if (httpURLConnection != null) {
                        httpURLConnection.disconnect();
                    }
                }
            }
            if (count > retryCount) {
                for (Listener downloadListener : listenerSet) {
                    downloadListener.error("连接超时！");
                }
                throw new M3u8Exception("连接超时！");
            }

//            LogUtil.i("读取到的m3u8文本：\n" + content);

            return content;
        }

        /**
         * 解密ts
         *
         * @param sSrc   ts文件字节数组
         * @param length
         * @param sKey   密钥
         * @return 解密后的字节数组
         */
        private byte[] decrypt(byte[] sSrc, int length, String sKey, String iv, String method) throws Exception {
            if (StringUtils.isNotEmpty(method) && !method.contains("AES")) {

                for (Listener downloadListener : listenerSet) {
                    downloadListener.error("解密视频文件出错：未知的算法！");
                }
                throw new M3u8Exception("未知的算法！");

            }
            // 判断Key是否正确
            if (StringUtils.isEmpty(sKey))
                return null;
            // 判断Key是否为16位
            if (sKey.length() != 16 && !isByte) {
                for (Listener downloadListener : listenerSet) {
                    downloadListener.error("解密视频文件出错：Key长度不是16位！");
                }
                throw new M3u8Exception("Key长度不是16位！");
            }

            LogUtil.i("开始解密");
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
            SecretKeySpec keySpec = new SecretKeySpec(isByte ? keyBytes : sKey.getBytes(StandardCharsets.UTF_8), "AES");
            byte[] ivByte;
            if (iv.startsWith("0x"))
                ivByte = StringUtils.hexStringToByteArray(iv.substring(2));
            else ivByte = iv.getBytes();
            if (ivByte.length != 16)
                ivByte = new byte[16];
            //如果m3u8有IV标签，那么IvParameterSpec构造函数就把IV标签后的内容转成字节数组传进去
            AlgorithmParameterSpec paramSpec = new IvParameterSpec(ivByte);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, paramSpec);
            return cipher.doFinal(sSrc, 0, length);
        }

        /**
         * 字段校验
         */
        private void checkField() {

            if ("m3u8".compareTo(MediaFormat.getMediaFormat(DOWNLOADURL)) != 0) {
                for (Listener downloadListener : listenerSet) {
                    downloadListener.error(DOWNLOADURL + "不是一个完整m3u8链接！");
                }
                throw new M3u8Exception(DOWNLOADURL + "不是一个完整m3u8链接！");
            }
            if (threadCount <= 0) {
                for (Listener downloadListener : listenerSet) {
                    downloadListener.error("同时下载线程数只能大于0！");
                }
                throw new M3u8Exception("同时下载线程数只能大于0！");
            }
            if (retryCount < 0) {
                for (Listener downloadListener : listenerSet) {
                    downloadListener.error("重试次数不能小于0！");
                }
                throw new M3u8Exception("重试次数不能小于0！");
            }
            if (timeoutMillisecond < 0) {
                for (Listener downloadListener : listenerSet) {
                    downloadListener.error("超时时间不能小于0！");
                }
                throw new M3u8Exception("超时时间不能小于0！");
            }
            if (StringUtils.isEmpty(dir)) {
                for (Listener downloadListener : listenerSet) {
                    downloadListener.error("视频存储目录不能为空！");
                }
                throw new M3u8Exception("文件存储目录不能为空！");
            }
            if (StringUtils.isEmpty(fileName)) {
                for (Listener downloadListener : listenerSet) {
                    downloadListener.error("文件名称不能为空！");
                }
                throw new M3u8Exception("文件名称不能为空！");
            }
            finishedCount = 0;
            method = "";
            key = "";
            isByte = false;
            iv = "";
            tsSet.clear();
            finishedFiles.clear();
            downloadBytes = new BigDecimal(0);
        }

        //拼接ts文件的下载地址
        private String mergeUrl(String start, String end) {
            if (end.startsWith("/"))
                end = end.replaceFirst("/", "");
            for (String s1 : end.split("/")) {
                if (start.contains(s1))
                    start = start.replace(s1 + "/", "");
            }
            return start + end;
        }

        public String getDOWNLOADURL() {
            return DOWNLOADURL;
        }

        public int getThreadCount() {
            return threadCount;
        }


        // *************************  对外开放的设置项   *******************************//


        /**
         * 设置下载的线程数
         *
         * @param threadCount
         */
        public void setThreadCount(int threadCount) {
            if (BLOCKING_QUEUE.size() < threadCount) {
                for (int i = BLOCKING_QUEUE.size(); i < threadCount * Constant.FACTOR; i++) {
                    try {
                        BLOCKING_QUEUE.put(new byte[Constant.BYTE_COUNT]);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
            this.threadCount = threadCount;
        }

        public int getRetryCount() {
            return retryCount;
        }

        public void setRetryCount(int retryCount) {
            this.retryCount = retryCount;
        }

        public long getTimeoutMillisecond() {
            return timeoutMillisecond;
        }

        public void setTimeoutMillisecond(long timeoutMillisecond) {
            this.timeoutMillisecond = timeoutMillisecond;
        }

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public int getFinishedCount() {
            return finishedCount;
        }

        public void setLogLevel(int level) {
            LogUtil.setLevel(level);
        }

        public Map<String, Object> getRequestHeaderMap() {
            return requestHeaderMap;
        }

        public void addRequestHeaderMap(Map<String, Object> requestHeaderMap) {
            this.requestHeaderMap.putAll(requestHeaderMap);
        }

        public void setInterval(long interval) {
            this.interval = interval;
        }

        public void addListener(Listener downloadListener) {
            listenerSet.add(downloadListener);
        }

        private M3u8Download(String DOWNLOADURL) {
            this.DOWNLOADURL = DOWNLOADURL;
            requestHeaderMap.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904" +
                    ".108 Safari/537.36");
            //开启一个任务时，先获取一个临时的文件夹，作为ts文件的临时下载路径，后续会删除
            Date day = new Date();
            @SuppressLint("SimpleDateFormat") SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
            mTempDir = Constant.FILESEPARATOR + df.format(day);
        }
    }

    /**
     * 获取实例
     *
     * @param downloadUrl 要下载的链接
     * @return 返回m3u8下载实例
     */
    @RequiresApi(api = Build.VERSION_CODES.N)
    public static M3u8Download getInstance(String downloadUrl) {
        if (m3u8Download == null) {
            synchronized (M3u8Download.class) {
                if (m3u8Download == null)
                    m3u8Download = new M3u8Download(downloadUrl);
            }
        }
        return m3u8Download;
    }

    public static void destroied() {
        m3u8Download = null;
    }

    public static boolean isDownloading() {
        return sIsDownloading;
    }


    public static class M3U8DownloadInfo {
        public int percent;
        public int sum;
        public int finished;
        public String speed;
        public String error;
        public String info;
        public String downloadSize;

        public M3U8DownloadInfo(int percent, int sum, int finished, String downloadSize) {
            this.percent = percent;
            this.sum = sum;
            this.finished = finished;
            this.downloadSize = downloadSize;
        }

        public M3U8DownloadInfo() {

        }
    }

}
