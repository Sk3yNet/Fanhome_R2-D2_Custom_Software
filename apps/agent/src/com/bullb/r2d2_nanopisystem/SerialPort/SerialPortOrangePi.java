package com.bullb.r2d2_nanopisystem.SerialPort;

import java.io.FileDescriptor;

/**
 * Shim with the exact class name and native signatures that the stock libr2d2.so
 * binds to (JNI names are derived from this package/class). The .so opens the tty
 * and sets the baud rate, which plain Java cannot do.
 * Do not rename this class, its package, or the "fd" field.
 */
public class SerialPortOrangePi {
    private FileDescriptor fd;

    private native FileDescriptor open(String path, int baudRate);

    private native void close1();

    public FileDescriptor openPort(String path, int baudRate) {
        fd = open(path, baudRate);
        return fd;
    }

    public void closePort() {
        if (fd != null) {
            close1();
            fd = null;
        }
    }
}
