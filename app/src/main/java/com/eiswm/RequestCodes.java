package com.eiswm;

/** Коды запросов startActivityForResult главного экрана: у каждого раздела свои, без пересечений. */
final class RequestCodes {
    static final int SOUNDS_ADD = 1;
    static final int SOUNDS_SAVE = 2;
    static final int PICTURES_ADD = 11;
    static final int PICTURES_SAVE = 12;
    static final int PICTURES_STANDARD = 13;
    static final int FAREWELL_ADD_SOUNDS = 21;
    static final int FAREWELL_ADD_PICTURES = 22;

    private RequestCodes() {}
}
