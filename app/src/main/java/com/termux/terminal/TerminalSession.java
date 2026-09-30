package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session coupled to a remote {@link Transport} (SSH) instead of a local process.
 * <p>
 * Emulation starts when the size is made known by {@link #updateSize(int, int, int, int)}, which also asks the
 * transport to connect. The transport pushes remote output with {@link #onRemoteData(byte[], int, int)} from any
 * thread; all terminal emulation and callback methods are performed on the main thread.
 */
public final class TerminalSession extends TerminalOutput {

    /** The remote end of the terminal. All methods are called on the main thread and must not block. */
    public interface Transport {
        /** Start connecting. Must call {@link #onConnected(OutputStream)} then {@link #onDisconnected(String)}. */
        void connect(TerminalSession session, int columns, int rows);

        void resize(int columns, int rows);

        void disconnect();
    }

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_DISCONNECTED = 2;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /** Written to by the transport reader thread, read by the main thread to feed the emulator. */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(256 * 1024);

    /** Written to from the main thread on user input, drained by the writer thread of the current connection. */
    private volatile ByteQueue mTerminalToRemoteQueue;

    /** Buffer to write translate code points into utf8 before writing to mTerminalToRemoteQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    TerminalSessionClient mClient;

    private final Transport mTransport;
    private final Integer mTranscriptRows;

    private volatile boolean mConnected;
    private boolean mConnecting;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    public TerminalSession(Transport transport, Integer transcriptRows, TerminalSessionClient client) {
        this.mTransport = transport;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the remote of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
            reconnect();
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            mTransport.resize(columns, rows);
        }
    }

    /** (Re)connect the transport, keeping the emulator and its scrollback. */
    public void reconnect() {
        if (mEmulator == null || mConnected || mConnecting) return;
        mConnecting = true;
        mTransport.connect(this, mEmulator.mColumns, mEmulator.mRows);
    }

    /** Called by the transport, from any thread, once the remote shell is ready to receive input. */
    public void onConnected(final OutputStream out) {
        final ByteQueue queue = new ByteQueue(64 * 1024);
        mTerminalToRemoteQueue = queue;
        mConnected = true;
        new Thread("TermOutputWriter") {
            @Override
            public void run() {
                final byte[] buffer = new byte[8192];
                try {
                    while (true) {
                        int bytesToWrite = queue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        out.write(buffer, 0, bytesToWrite);
                        out.flush();
                    }
                } catch (IOException e) {
                    // Connection gone, the reader side reports it.
                }
            }
        }.start();
    }

    /** Called by the transport reader thread with remote output. */
    public void onRemoteData(byte[] data, int offset, int count) {
        if (!mProcessToTerminalIOQueue.write(data, offset, count)) return;
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
    }

    /** Called by the transport, from any thread, when the connection ended or failed. */
    public void onDisconnected(String reason) {
        mConnected = false;
        ByteQueue queue = mTerminalToRemoteQueue;
        mTerminalToRemoteQueue = null;
        if (queue != null) queue.close();
        mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_DISCONNECTED, reason));
    }

    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /** Write data to the remote shell. Input typed while disconnected is dropped. */
    @Override
    public void write(byte[] data, int offset, int count) {
        ByteQueue queue = mTerminalToRemoteQueue;
        if (queue != null) queue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
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

    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Close the connection for good. */
    public void finishIfRunning() {
        mTransport.disconnect();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public boolean isRunning() {
        return mConnected;
    }

    public boolean isConnecting() {
        return mConnecting && !mConnected;
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

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            int bytesRead;
            boolean updated = false;
            // Drain a bounded amount so that bursts of output cause a single redraw without starving the UI thread.
            // On disconnect, drain everything so the status line comes after the last output.
            int budget = msg.what == MSG_DISCONNECTED ? Integer.MAX_VALUE : 4;
            while ((bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false)) > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                updated = true;
                if (--budget == 0) {
                    // More may be pending while the reader is blocked on a full queue: continue on the next loop.
                    sendEmptyMessage(MSG_NEW_INPUT);
                    break;
                }
            }
            if (updated) notifyScreenUpdate();

            if (msg.what == MSG_DISCONNECTED) {
                mConnecting = false;
                String reason = (String) msg.obj;
                if (reason != null && mEmulator != null) {
                    byte[] bytes = ("\r\n\u001b[0;90m[" + reason + "]\u001b[0m\r\n").getBytes(StandardCharsets.UTF_8);
                    mEmulator.append(bytes, bytes.length);
                    notifyScreenUpdate();
                }
                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
