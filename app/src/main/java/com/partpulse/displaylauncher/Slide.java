package com.partpulse.displaylauncher;

/**
 * A single slide item parsed from the server's SLIDES array.
 * Types: "pdf" (renders PDF page) or "notification" (renders overlay).
 */
public class Slide {
    public static final String TYPE_PDF = "pdf";
    public static final String TYPE_NOTIFICATION = "notification";

    public String type;
    public String label;
    public String url;
    public String title;
    public String body;
    public String date;
    public String localPath;
    public int duration = 10; // seconds to display this slide
}
