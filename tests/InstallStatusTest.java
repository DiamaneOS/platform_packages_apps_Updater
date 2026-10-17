package app.seamlessupdate.client;

public final class InstallStatusTest {
    private static void check(boolean v) { if (!v) throw new AssertionError(); }
    public static void main(String[] args) {
        // A pick reserves the service until the service has handled that pick.
        check(InstallStatus.claim(1) && InstallStatus.busy() && InstallStatus.titleResId() == 1);
        check(!InstallStatus.claim(1));
        InstallStatus.started();
        InstallStatus.show(2, 40);
        check(!InstallStatus.claim(1) && InstallStatus.percent() == 40);
        InstallStatus.finished(true);
        check(!InstallStatus.busy() && InstallStatus.titleResId() == 2);
        // An online run blocks picks while it runs and leaves a pending pick reserved.
        InstallStatus.started();
        check(!InstallStatus.claim(1));
        InstallStatus.finished(false);
        check(InstallStatus.claim(1));
        InstallStatus.started();
        InstallStatus.finished(false);
        check(InstallStatus.busy());
        InstallStatus.finished(true);
        check(!InstallStatus.busy());
    }
}
