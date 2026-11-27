package vn.labaccess.desktop;

interface DemoScript {

    DemoScript NONE = new DemoScript() {

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    };



    static DemoScript create(App app) {
        return NONE;
    }

    void start();

    void stop();
}
