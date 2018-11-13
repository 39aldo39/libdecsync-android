// Based of FolderObserver from syncthing-android
// <https://github.com/syncthing/syncthing-android/blob/203dfc753f3c71370e12e8206ec028979aa1d325/app/src/main/java/com/nutomic/syncthingandroid/util/FolderObserver.java>

package org.decsync.library;

import android.os.FileObserver;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;

/**
 * Recursively watches a directory and all subfolders.
 */
public class FolderObserver extends FileObserver {

    private static final String TAG = "FolderObserver";

    private final OnFolderFileChangeListener mListener;

    private final File mFolder;

    private final String mPath;

    private final ArrayList<FolderObserver> mChilds = new ArrayList<>();

    public interface OnFolderFileChangeListener {
        public void onFolderFileChange(File folder, String relativePath);
    }

    public FolderObserver(OnFolderFileChangeListener listener, File folder)
            throws FolderNotExistingException {
        this(listener, folder, "");
    }

    public class FolderNotExistingException extends Exception {

        private final String mPath;

        public FolderNotExistingException(String path) {
            mPath = path;
        }

        @Override
        public String getMessage() {
            return "path " + mPath + " does not exist, aborting file observer";
        }
    }

    /**
     * Constructs watcher and starts watching the given directory recursively.
     *
     * @param listener The listener where changes should be sent to.
     * @param folder The folder where this folder belongs to.
     * @param path path to the monitored folder, relative to folder root.
     */
    private FolderObserver(OnFolderFileChangeListener listener, File folder, String path)
            throws FolderNotExistingException {
        super(folder + "/" + path,
                ATTRIB | CLOSE_WRITE | CREATE | DELETE | DELETE_SELF | MOVED_FROM |
                        MOVED_TO | MOVE_SELF);
        mListener = listener;
        mFolder = folder;
        mPath = path;
        Log.v(TAG, "Observer created for " + new File(mFolder, mPath).toString() + " (folder " + folder + ")");
        startWatching();

        File currentFolder = new File(folder, path);
        if (!currentFolder.exists()) {
            throw new FolderNotExistingException(currentFolder.getAbsolutePath());
        }
        File[] directories = currentFolder.listFiles((current, name) -> new File(current, name).isDirectory());

        if (directories != null) {
            for (File f : directories) {
                mChilds.add(new FolderObserver(mListener, mFolder, path + "/" + f.getName()));
            }
        }
    }

    /**
     * Handles incoming events for changed files.
     */
    @Override
    public void onEvent(int event, String path) {
        // Ignore some weird events that we may receive.
        event &= FileObserver.ALL_EVENTS;
        if (event == 0)
            return;

        File fullPath = (path != null)
                ? new File(mPath, path)
                : new File(mPath);

        Log.v(TAG, "Received inotify event " + Integer.toHexString(event) + " at " +
                fullPath.getAbsolutePath());
        switch (event) {
            case MOVED_FROM:
                // fall through
            case DELETE_SELF:
                // fall through
            case DELETE:
                for (FolderObserver c : mChilds) {
                    if (c.mPath.equals(path)) {
                        mChilds.remove(c);
                        break;
                    }
                }
                mListener.onFolderFileChange(mFolder, fullPath.getPath());
                break;
            case MOVED_TO:
                // fall through
            case CREATE:
                if (fullPath.isDirectory()) {
                    try {
                        mChilds.add(new FolderObserver(mListener, mFolder, path));
                    } catch (FolderNotExistingException e) {
                        Log.w(TAG, "Failed to add listener for nonexisting folder", e);
                    }
                }
                // fall through
            default:
                mListener.onFolderFileChange(mFolder, fullPath.getPath());
        }
    }

    /**
     * Recursively stops watching the directory.
     */
    @Override
    public void stopWatching() {
        super.stopWatching();
        for (FolderObserver ro : mChilds) {
            ro.stopWatching();
        }
    }
}
