package com.termux.terminal;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session: a terminal emulator coupled to something that produces and consumes its
 * bytes.
 * <p>
 * This file replaces Termux's {@code TerminalSession}, which starts a local process and talks to
 * it through a pseudo-terminal. Here the other end is a {@link Transport} supplied by the
 * application, for example a network connection to a remote shell. The public methods that
 * {@code TerminalView} and {@link TerminalSessionClient} rely on are kept, so those classes are
 * used unmodified.
 * <p>
 * Terminal emulation starts when the size is first made known by {@link #updateSize(int, int, int, int)}.
 * All emulation and all callbacks to the {@link TerminalSessionClient} happen on the main thread.
 * {@link #onRemoteOutput(byte[], int, int)} and {@link #onRemoteClosed(String)} may be called
 * from any thread.
 * <p>
 * NOTE: The terminal session may outlive the view showing it, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    /** The other end of the terminal. Called on the main thread; must not block. */
    public interface Transport {
        /** Send bytes the person typed, or that the terminal answers with, to the other end. */
        void write(byte[] data, int offset, int count);

        /** Tell the other end the terminal's new size in character cells. */
        void resize(int columns, int rows);

        /** Close the connection. Called at most once. */
        void close();
    }

    private static final int MSG_NEW_OUTPUT = 1;
    private static final int MSG_CLOSED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from another thread when the other end produces output, and read by the
     * main thread to be processed by the terminal emulator. A full queue blocks the writer, which
     * slows the connection down to the speed the emulator can keep up with.
     */
    final ByteQueue mRemoteToTerminalQueue = new ByteQueue(64 * 1024);

    /** Buffer to translate code points into UTF-8 before writing them to the transport. */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    private final Transport mTransport;
    private final Integer mTranscriptRows;

    /** Accessed on the main thread only, except through {@link #isRunning()}. */
    private volatile boolean mRunning = true;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler(Looper.getMainLooper());

    /**
     * @param transport      Where typed bytes and size changes are sent.
     * @param transcriptRows The number of scrollback rows to keep, or null for the default.
     * @param client         Receives callbacks about this session.
     */
    public TerminalSession(Transport transport, Integer transcriptRows, TerminalSessionClient client) {
        this.mTransport = transport;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the other end of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
            // Output may have arrived before the size was known. Process it now.
            mMainThreadHandler.sendEmptyMessage(MSG_NEW_OUTPUT);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
        if (mRunning) mTransport.resize(columns, rows);
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Feed output from the other end into the terminal. May be called from any thread, and blocks
     * while the terminal is too far behind.
     */
    public void onRemoteOutput(byte[] data, int offset, int count) {
        if (count <= 0) return;
        if (!mRemoteToTerminalQueue.write(data, offset, count)) return;
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_OUTPUT);
    }

    /**
     * Tell the session that the other end is gone. May be called from any thread.
     *
     * @param message A short explanation shown in the terminal, or null for none.
     */
    public void onRemoteClosed(String message) {
        mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_CLOSED, message));
    }

    /** Write data to the other end. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mRunning) mTransport.write(data, offset, count);
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
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by closing the connection to the other end. */
    public void finishIfRunning() {
        if (mRunning) {
            mRunning = false;
            mRemoteToTerminalQueue.close();
            mTransport.close();
        }
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public boolean isRunning() {
        return mRunning;
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

    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        MainThreadHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            // Until the size is known there is no emulator to feed. The output stays queued.
            if (mEmulator != null) {
                int bytesRead = mRemoteToTerminalQueue.read(mReceiveBuffer, false);
                if (bytesRead > 0) {
                    mEmulator.append(mReceiveBuffer, bytesRead);
                    notifyScreenUpdate();
                }
            }

            if (msg.what == MSG_CLOSED) {
                boolean wasRunning = mRunning;
                mRunning = false;
                mRemoteToTerminalQueue.close();

                String message = (String) msg.obj;
                if (mEmulator != null && message != null && !message.isEmpty()) {
                    byte[] bytesToWrite = ("\r\n[" + message + "]").getBytes(StandardCharsets.UTF_8);
                    mEmulator.append(bytesToWrite, bytesToWrite.length);
                    notifyScreenUpdate();
                }

                if (wasRunning) mTransport.close();
                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
