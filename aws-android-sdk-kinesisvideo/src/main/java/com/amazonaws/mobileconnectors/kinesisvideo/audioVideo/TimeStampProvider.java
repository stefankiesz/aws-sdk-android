package com.amazonaws.mobileconnectors.kinesisvideo.audiovideo;



public class TimeStampProvider {

    public static synchronized long getUniqueTimestamp() {
        try {
            Thread.sleep(1);
        } catch (Exception e) {
            System.out.println("getUniqueTimestamp thread sleep was interrupted.");
        }

        return System.currentTimeMillis();
    }
}
