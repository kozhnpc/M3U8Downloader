package work.kozh.m3u8.listener;

public interface Listener {

    void start();

    void process(String downloadUrl, int finished, int sum, float percent);

    void speed(String speedPerSecond);

    void error(String msg);

    void info(String msg);

    void end();

}
