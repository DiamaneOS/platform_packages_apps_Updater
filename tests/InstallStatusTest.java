package app.seamlessupdate.client;

public final class InstallStatusTest {
    private static void check(boolean v) { if (!v) throw new AssertionError(); }
    public static void main(String[] args) {
        // A pick reserves the service until the service has handled that pick.
        check(InstallStatus.claim(1) && InstallStatus.busy() && InstallStatus.titleResId() == 1);
        check(!InstallStatus.claim(1) && !InstallStatus.claimCheck(1));
        InstallStatus.started(true, 0);
        InstallStatus.show(2, 40);
        check(!InstallStatus.claim(1) && InstallStatus.percent() == 40);
        InstallStatus.finished(true, false);
        check(!InstallStatus.busy() && InstallStatus.titleResId() == 2);
        // A step reported after the run ended is dropped, so the result stays on screen.
        InstallStatus.show(3, 10);
        check(InstallStatus.titleResId() == 2 && InstallStatus.percent() == 40);
        // An online run blocks picks and checks while it runs and leaves a pending pick reserved.
        InstallStatus.started(false, 0);
        check(!InstallStatus.claim(1) && !InstallStatus.claimCheck(1));
        InstallStatus.finished(false, false);
        check(InstallStatus.claim(1));
        InstallStatus.started(false, 0);
        InstallStatus.finished(false, false);
        check(InstallStatus.busy());
        InstallStatus.finished(true, false);
        check(!InstallStatus.busy());
        // A check from the screen holds its reservation until the service finished that check.
        check(InstallStatus.claimCheck(5) && InstallStatus.busy() && InstallStatus.shownBusy(0));
        InstallStatus.started(true, 0);
        InstallStatus.target("2026112000");
        InstallStatus.finished(false, true);
        check(!InstallStatus.busy() && "2026112000".equals(InstallStatus.target()));
        // A reservation whose service never started is given back, so the screen is not greyed for good.
        check(InstallStatus.claimCheck(5));
        InstallStatus.release(false);
        check(!InstallStatus.busy() && InstallStatus.titleResId() == 0 && InstallStatus.claim(1));
        InstallStatus.release(true);
        check(!InstallStatus.busy());
        // A periodic run shows as busy only after its first second, a claimed one at once.
        InstallStatus.started(false, 10_000);
        check(InstallStatus.busy() && !InstallStatus.shownBusy(10_500) && InstallStatus.shownBusy(11_000));
        check(InstallStatus.target() == null);
        InstallStatus.finished(false, false);
        check(!InstallStatus.shownBusy(20_000));
        // Percentages stay within 0 to 100, and unknown progress is -1.
        check(InstallStatus.claimCheck(1));
        InstallStatus.show(4, 250);
        check(InstallStatus.percent() == 100);
        InstallStatus.show(4, -7);
        check(InstallStatus.percent() == -1);
        InstallStatus.release(false);
    }
}
