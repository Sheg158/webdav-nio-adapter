package org.cryptomator.frontend.webdav.mount;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;

class MacAppleScriptMounter implements MounterStrategy {

	private static final Logger LOG = LoggerFactory.getLogger(MacAppleScriptMounter.class);
	private static final boolean IS_OS_MACOSX = System.getProperty("os.name").contains("Mac OS X");
	private static final String[] OS_VERSION = Iterables.toArray(Splitter.on('.').splitToList(System.getProperty("os.version")), String.class);
	private static final Pattern MOUNT_PATTERN = Pattern.compile(".* on (\\S+) \\(.*\\)"); // catches mount point in "http://host:port/foo/ on /Volumes/foo (webdav, nodev, noexec, nosuid)"

	@Override
	public boolean isApplicable() {
		try {
			return IS_OS_MACOSX && OS_VERSION.length >= 2 && Integer.parseInt(OS_VERSION[1]) >= 10; // since macOS 10.10+
		} catch (NumberFormatException e) {
			return false;
		}
	}

	@Override
	public Mount mount(URI uri, MountParams mountParams) throws CommandFailedException {
		try {
			// mount:
			String mountAppleScript = String.format("mount volume \"%s\" as user name \"anonymous\" with password \"\"", uri.toASCIIString());
			ProcessBuilder mount = new ProcessBuilder("/usr/bin/osascript", "-e", mountAppleScript);
			Process mountProcess = mount.start();
			ProcessUtil.waitFor(mountProcess, 60, TimeUnit.SECONDS); // huge timeout since the user might need to confirm connecting via http
			ProcessUtil.assertExitValue(mountProcess, 0);

			// verify mounted:
			ProcessBuilder verifyMount = new ProcessBuilder("/bin/sh", "-c", "mount | grep \"" + uri.toASCIIString() + "\"");
			Process verifyProcess = verifyMount.start();
			String stdout = ProcessUtil.toString(verifyProcess.getInputStream(), StandardCharsets.UTF_8);
			ProcessUtil.waitFor(verifyProcess, 10, TimeUnit.SECONDS);
			ProcessUtil.assertExitValue(mountProcess, 0);

			// determine mount point:
			Matcher mountPointMatcher = MOUNT_PATTERN.matcher(stdout);
			if (mountPointMatcher.find()) {
				String mountPoint = mountPointMatcher.group(1);
				LOG.debug("Mounted {} on {}.", uri.toASCIIString(), mountPoint);
				return new MountImpl(Paths.get(mountPoint));
			} else {
				throw new CommandFailedException("Mount succeeded, but failed to determine mount point in string: " + stdout);
			}
		} catch (IOException e) {
			throw new CommandFailedException(e);
		}
	}

	private static class MountImpl implements Mount {

		private final Path mountPath;
		private final ProcessBuilder revealCommand;
		private final ProcessBuilder unmountCommand;

		private MountImpl(Path mountPath) {
			this.mountPath = mountPath;
			this.revealCommand = new ProcessBuilder("open", mountPath.toString());
			this.unmountCommand = new ProcessBuilder("sh", "-c", "diskutil umount \"" + mountPath + "\"");
		}

		@Override
		public void unmount() throws CommandFailedException {
			if (!Files.isDirectory(mountPath)) {
				// unmounting a mounted drive will delete the associated mountpoint (at least under OS X 10.11)
				LOG.debug("Volume already unmounted.");
				return;
			}
			ProcessUtil.assertExitValue(ProcessUtil.startAndWaitFor(unmountCommand, 10, TimeUnit.SECONDS), 0);
			try {
				Files.deleteIfExists(mountPath);
			} catch (IOException e) {
				LOG.warn("Could not delete {} due to exception: {}", mountPath, e.getMessage());
			}
		}

		@Override
		public void reveal() throws CommandFailedException {
			ProcessUtil.assertExitValue(ProcessUtil.startAndWaitFor(revealCommand, 10, TimeUnit.SECONDS), 0);
		}

	}

}
