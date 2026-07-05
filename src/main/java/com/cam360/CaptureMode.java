package com.cam360;

public enum CaptureMode {
    SEPARATE,
    AUTO_STITCH;

    public CaptureMode next() {
        return this == SEPARATE ? AUTO_STITCH : SEPARATE;
    }
}
