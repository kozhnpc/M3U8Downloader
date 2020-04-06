package work.kozh.m3u8.exception;


public class M3u8Exception extends RuntimeException {
    public M3u8Exception() {
        super();
    }

    public M3u8Exception(String message) {
        super(message);
    }

    public M3u8Exception(String message, Throwable cause) {
        super(message, cause);
    }
}
