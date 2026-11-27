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
        try {
            Class<?> type = Class.forName("vn.labaccess.desktop.DemoScenario");
            return (DemoScript) type.getDeclaredMethod("create", App.class).invoke(null, app);
        } catch (ReflectiveOperationException | LinkageError absent) {
            return NONE;
        }
    }

    void start();

    void stop();
}
