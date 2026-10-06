package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PyCode 版 TerminalSession：**只走 socket**（连到 Termux 里的「终端桥」127.0.0.1:8768）。
 *
 * <p>和 Termux 原版的区别：原版用 {@code JNI.createSubprocess()} 在本 App 里 fork 一个 pty
 * （所以必须带原生库 libtermux.so）；我们把会话层改成 socket：
 * <ul>
 *   <li>不需要原生库、不需要 NDK</li>
 *   <li>界面里显示/操作的却是**真 Termux 环境**（bash / python / pkg 都是真机里那套）</li>
 * </ul>
 *
 * <p>协议（对应 /sdcard/PythonProjects/_engine/tty_bridge.py）：
 * <pre>
 * 客户端 → 桥（每行一个 JSON）：
 *   {"t":"o","cmd":"bash -l","cwd":"/sdcard/PythonProjects","rows":24,"cols":80,"cw":8,"ch":16}
 *   {"t":"i","d":"&lt;base64 的原始按键字节&gt;"}
 *   {"t":"s","rows":24,"cols":80,"cw":8,"ch":16}
 *   {"t":"e"}
 * 桥 → 客户端：纯终端字节流
 * </pre>
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /** 子线程写入（桥的输出），主线程读取后喂给终端模拟器。 */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    /** 主线程写入（用户按键），另一个线程读取后发往 socket。 */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** 把码点编成 UTF-8 用的缓冲。 */
    private final byte[] mUtf8InputBuffer = new byte[5];

    TerminalSessionClient mClient;

    /** 兼容原版语义：>0 表示会话在跑，-1 表示已结束（socket 模式下没有真实 pid）。 */
    int mShellPid;
    int mShellExitStatus;

    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final String mHost;
    private final int mPort;
    private final String mExtraOpenJson;
    private final Integer mTranscriptRows;

    private final Object mSocketLock = new Object();
    /**
     * 控制类消息（open / resize / end）的写线程。
     * <p>Android 不允许在主线程做 socket 写，而「改字号 → updateSize → resize」正好发生在主线程，
     * 所以这些消息必须丢到这个后台单线程里写（FIFO，保证 open 先于 resize）。
     */
    private final ExecutorService mControlExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TermSessionControl");
        t.setDaemon(true); // 别让它拖住进程
        return t;
    });
    private volatile Socket mSocket;
    private volatile OutputStream mSocketOut;
    private volatile boolean mClosedByUser;

    private static final String LOG_TAG = "TerminalSession";

    /**
     * @param host          终端桥地址，一般是 {@code 127.0.0.1}
     * @param port          终端桥端口，8768
     * @param extraOpenJson 额外拼进 open 消息里的 JSON 片段，形如
     *                      {@code "cmd":"bash -l","cwd":"/sdcard/PythonProjects"}，可为 null
     * @param transcriptRows 回滚缓冲行数，可为 null
     * @param client        会话回调
     */
    public TerminalSession(String host, int port, String extraOpenJson, Integer transcriptRows,
                           TerminalSessionClient client) {
        this.mHost = host;
        this.mPort = port;
        this.mExtraOpenJson = extraOpenJson;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) mEmulator.updateTerminalSessionClient(client);
    }

    /** 尺寸变化：首次调用会建立会话，之后只通知桥调整远端 pty 大小。 */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        Logger.logInfo(mClient, LOG_TAG, "updateSize: cols=" + columns + " rows=" + rows
            + " emulator=" + (mEmulator != null));
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            sendResizeJson(rows, columns, cellWidthPixels, cellHeightPixels);
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /** 建立会话：连桥 → 发 open → 起读/写线程。 */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels,
            mTranscriptRows, mClient);

        // write() 会检查 mShellPid > 0，这里先标记成"运行中"
        mShellPid = 1;
        mClient.setTerminalShellPid(this, mShellPid);

        final int fRows = rows;
        final int fCols = columns;
        final int fCw = cellWidthPixels;
        final int fCh = cellHeightPixels;

        new Thread("TermSessionConnect") {
            @Override
            public void run() {
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(mHost, mPort), 5000);
                    s.setTcpNoDelay(true);
                    synchronized (mSocketLock) {
                        mSocket = s;
                        mSocketOut = s.getOutputStream();
                    }

                    StringBuilder sb = new StringBuilder(128);
                    sb.append("{\"t\":\"o\"");
                    if (mExtraOpenJson != null && mExtraOpenJson.length() > 0) {
                        sb.append(',').append(mExtraOpenJson);
                    }
                    sb.append(",\"rows\":").append(fRows)
                        .append(",\"cols\":").append(fCols)
                        .append(",\"cw\":").append(fCw)
                        .append(",\"ch\":").append(fCh)
                        .append("}\n");
                    final OutputStream out = mSocketOut;
                    final String openLine = sb.toString();
                    mControlExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                synchronized (mSocketLock) {
                                    out.write(openLine.getBytes(StandardCharsets.UTF_8));
                                    out.flush();
                                }
                            } catch (Exception e) {
                                Logger.logStackTraceWithMessage(mClient, LOG_TAG, "open 发送失败", e);
                            }
                        }
                    });

                    startReader(s);
                    startWriter();
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(mClient, LOG_TAG, "连接终端桥失败", e);
                    feedText("\r\n\u001b[31m无法连接终端桥 (127.0.0.1:" + mPort + ")\u001b[0m\r\n"
                        + e.getClass().getSimpleName() + ": " + e.getMessage() + "\r\n\r\n"
                        + "提示：终端桥会在 PyCode 启动时自动拉起；也可以手动执行：\r\n"
                        + "  sh ~/tty.sh start\r\n");
                    mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, 1));
                }
            }
        }.start();
    }

    private void feedText(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        mProcessToTerminalIOQueue.write(bytes, 0, bytes.length);
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
    }

    /** 桥 → 队列（读到 EOF 就认为会话结束）。 */
    private void startReader(final Socket s) {
        new Thread("TermSessionInputReader") {
            @Override
            public void run() {
                try (InputStream in = s.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    while (true) {
                        int read = in.read(buffer);
                        if (read == -1) return;
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) return;
                        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                    }
                } catch (Exception ignored) {
                    // 会话结束
                } finally {
                    mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, 0));
                }
            }
        }.start();
    }

    /** 队列 → 桥（按键按 base64 编码成一行 JSON）。 */
    private void startWriter() {
        new Thread("TermSessionOutputWriter") {
            @Override
            public void run() {
                byte[] buffer = new byte[4096];
                while (true) {
                    int n = mTerminalToProcessIOQueue.read(buffer, true);
                    if (n == -1) return;
                    OutputStream out = mSocketOut;
                    if (out == null) return;
                    try {
                        String line = "{\"t\":\"i\",\"d\":\""
                            + Base64.getEncoder().encodeToString(Arrays.copyOf(buffer, n)) + "\"}\n";
                        synchronized (mSocketLock) {
                            out.write(line.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    } catch (Exception e) {
                        return;
                    }
                }
            }
        }.start();
    }

    private void sendResizeJson(final int rows, final int cols, final int cw, final int ch) {
        final OutputStream out = mSocketOut;
        if (out == null) {
            Logger.logWarn(mClient, LOG_TAG, "resize 丢弃：socket 还没连上");
            return;
        }
        // 注意：这里通常是在主线程被调用的，绝不能直接写 socket（NetworkOnMainThreadException），
        // 必须交给后台线程。
        mControlExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String line = "{\"t\":\"s\",\"rows\":" + rows + ",\"cols\":" + cols
                        + ",\"cw\":" + cw + ",\"ch\":" + ch + "}\n";
                    synchronized (mSocketLock) {
                        out.write(line.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    Logger.logInfo(mClient, LOG_TAG, "resize 已发送: " + rows + "x" + cols);
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(mClient, LOG_TAG, "resize 发送失败", e);
                }
            }
        });
    }

    /** 写数据到会话（TerminalView / 键盘 / 扩展键都走这里）。 */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mShellPid > 0) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** 写一个 Unicode 码点（UTF-8 编码后送出去）。 */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= 0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= 0b11111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= 0b1111111111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** 通知 client 屏幕变了。 */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** 结束会话：让桥关掉远端 shell，然后断开连接。 */
    public void finishIfRunning() {
        if (!isRunning()) return;
        mClosedByUser = true;
        final OutputStream out = mSocketOut;
        if (out != null) {
            try {
                mControlExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // 桥在连接断开时本来就会收掉子进程；这里只是尽量先打个招呼
                            synchronized (mSocketLock) {
                                out.write("{\"t\":\"e\"}\n".getBytes(StandardCharsets.UTF_8));
                                out.flush();
                            }
                        } catch (Exception ignored) {
                            // 已经断了
                        }
                    }
                });
                Thread.sleep(120); // 给它一点时间把 'e' 发出去
            } catch (Exception ignored) {
            }
        }
        closeSocket();
    }

    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mShellPid = -1;
            mShellExitStatus = exitStatus;
        }
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        closeSocket();
        try {
            mControlExecutor.shutdownNow();
        } catch (Exception ignored) {
        }
    }

    private void closeSocket() {
        synchronized (mSocketLock) {
            try {
                if (mSocketOut != null) mSocketOut.close();
            } catch (Exception ignored) {
            }
            try {
                if (mSocket != null) mSocket.close();
            } catch (Exception ignored) {
            }
            mSocketOut = null;
            mSocket = null;
        }
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mShellPid != -1;
    }

    /** 只有 {@link #isRunning()} 为 false 时才有意义。 */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    public int getPid() {
        return mShellPid;
    }

    /** socket 模式下拿不到远端 shell 的工作目录。 */
    public String getCwd() {
        return null;
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        @Override
        public void handleMessage(Message msg) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_PROCESS_EXITED) {
                int exitCode = (Integer) msg.obj;
                cleanupResources(exitCode);
                // 桥那边已经会打印 "[进程已结束]"；这里只补一句，避免出现两段提示
                if (!mClosedByUser) {
                    feedText("\r\n\u001b[33m[会话已断开]\u001b[0m\r\n");
                }
                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
