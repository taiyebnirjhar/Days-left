package com.taiyeb.daysleft;

/** Something that reads the book sentence by sentence: the phone's own speech engine (Speaker) or the natural voice (NeuralSpeaker). */
interface Narrator {
    interface Listener {
        void onSentence(int from, int to);   // this sentence has started (main thread)
        void onEnd();                        // the book is finished
        void onState();                      // playing / paused / ready / failed changed
        default void onSlow() {}             // the natural voice cannot keep up: use the phone's own
    }
    boolean ready();
    boolean failed();
    boolean playing();
    void setBook(BookIndex b, int itemId);
    void setRate(float r);
    void playFrom(int word);
    void pause();
    void shutdown();
    int position();
}
