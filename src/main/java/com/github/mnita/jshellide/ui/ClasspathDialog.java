package com.github.mnita.jshellide.ui;

import com.github.mnita.jshellide.maven.MavenDependencyResolver;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Dialog for configuring Maven dependencies (`groupId:artifactId:version`) and
 * resolved/local classpath entries (JARs, folders), with status markers for
 * downloaded Maven artifacts and active classpath entries.
 */
public class ClasspathDialog {

    public static final String MARKER_ACTIVE = "[✓] ";
    public static final String MARKER_INACTIVE = "[ ] ";

    private final Shell parent;
    private final MavenDependencyResolver mavenResolver;
    private final List<String> mavenCoordinates;
    private final List<String> classpathEntries;
    private final Set<String> activeClasspathSet;
    private boolean confirmed = false;

    public ClasspathDialog(
            Shell parent,
            MavenDependencyResolver mavenResolver,
            List<String> currentMavenCoords,
            List<String> currentClasspathEntries,
            List<String> currentActiveClasspath) {
        this.parent = parent;
        this.mavenResolver = mavenResolver;
        this.mavenCoordinates = new ArrayList<>(
                currentMavenCoords != null ? new LinkedHashSet<>(currentMavenCoords) : List.of());
        this.classpathEntries = new ArrayList<>(
                currentClasspathEntries != null ? new LinkedHashSet<>(currentClasspathEntries) : List.of());
        this.activeClasspathSet = new LinkedHashSet<>(
                currentActiveClasspath != null ? currentActiveClasspath : List.of());
        // Ensure any active entry is also present in classpathEntries
        for (String active : this.activeClasspathSet) {
            if (!this.classpathEntries.contains(active)) {
                this.classpathEntries.add(active);
            }
        }
    }

    /** Formats a list entry with a read-only status marker (`[✓] ` or `[ ] `). */
    public static String formatListItem(boolean activeOrDownloaded, String text) {
        return (activeOrDownloaded ? MARKER_ACTIVE : MARKER_INACTIVE) + text;
    }

    /** Strips the leading status marker (`[✓] ` or `[ ] `) from a formatted list item. */
    public static String stripMarker(String formattedItem) {
        if (formattedItem == null) {
            return "";
        }
        if (formattedItem.startsWith(MARKER_ACTIVE)) {
            return formattedItem.substring(MARKER_ACTIVE.length());
        }
        if (formattedItem.startsWith(MARKER_INACTIVE)) {
            return formattedItem.substring(MARKER_INACTIVE.length());
        }
        return formattedItem;
    }

    /** Opens the modal dialog and returns {@code true} if the user clicked Apply & Close. */
    public boolean open() {
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        dialog.setText("Classpath & Maven Dependencies");
        dialog.setLayout(new GridLayout(1, false));
        dialog.setSize(760, 580);

        // Group 1: Maven Dependencies
        Group mavenGroup = new Group(dialog, SWT.NONE);
        mavenGroup.setText("Maven Dependencies ([✓] = Downloaded in local repo)");
        mavenGroup.setLayout(new GridLayout(4, false));
        mavenGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Text coordInput = new Text(mavenGroup, SWT.BORDER);
        coordInput.setMessage("e.g. com.google.code.gson:gson:2.11.0");
        coordInput.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button addMavenBtn = new Button(mavenGroup, SWT.PUSH);
        addMavenBtn.setText("Add");

        Button removeMavenBtn = new Button(mavenGroup, SWT.PUSH);
        removeMavenBtn.setText("Remove Selected");

        Button downloadMavenBtn = new Button(mavenGroup, SWT.PUSH);
        downloadMavenBtn.setText("Download Selected");

        org.eclipse.swt.widgets.List mavenList =
                new org.eclipse.swt.widgets.List(
                        mavenGroup, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        GridData mavenListData = new GridData(SWT.FILL, SWT.FILL, true, true, 4, 1);
        mavenListData.heightHint = 120;
        mavenList.setLayoutData(mavenListData);

        // Group 2: Resolved Classpath Entries (JARs & Folders)
        Group cpGroup = new Group(dialog, SWT.NONE);
        cpGroup.setText("Resolved Classpath Entries ([✓] = Currently in Classpath)");
        cpGroup.setLayout(new GridLayout(5, false));
        cpGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        org.eclipse.swt.widgets.List cpList =
                new org.eclipse.swt.widgets.List(
                        cpGroup, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        GridData cpListData = new GridData(SWT.FILL, SWT.FILL, true, true, 5, 1);
        cpListData.heightHint = 170;
        cpList.setLayoutData(cpListData);

        Button addJarBtn = new Button(cpGroup, SWT.PUSH);
        addJarBtn.setText("Add JAR(s)...");

        Button addDirBtn = new Button(cpGroup, SWT.PUSH);
        addDirBtn.setText("Add Directory...");

        Button removeCpBtn = new Button(cpGroup, SWT.PUSH);
        removeCpBtn.setText("Remove Selected");

        Button addToCpBtn = new Button(cpGroup, SWT.PUSH);
        addToCpBtn.setText("Add to Classpath");

        Button removeFromCpBtn = new Button(cpGroup, SWT.PUSH);
        removeFromCpBtn.setText("Remove from Classpath");

        Label statusLabel = new Label(dialog, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statusLabel.setText("Ready.");

        // Bottom OK / Cancel buttons
        Composite bottomBar = new Composite(dialog, SWT.NONE);
        bottomBar.setLayout(new GridLayout(2, true));
        bottomBar.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));

        Button okBtn = new Button(bottomBar, SWT.PUSH);
        okBtn.setText("Apply && Close");
        okBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button cancelBtn = new Button(bottomBar, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Initial list rendering
        refreshMavenList(mavenList);
        refreshClasspathList(cpList);

        // Listeners for Group 1: Maven Dependencies
        addMavenBtn.addListener(SWT.Selection, e -> {
            String coord = coordInput.getText().trim();
            if (!MavenDependencyResolver.isValidCoordinate(coord)) {
                showError(dialog, "Invalid Coordinate",
                        "Please enter a valid Maven coordinate in the form groupId:artifactId:version.");
                return;
            }
            if (!mavenCoordinates.contains(coord)) {
                mavenCoordinates.add(coord);
                boolean downloaded = mavenResolver.isCoordinateDownloaded(coord);
                mavenList.add(formatListItem(downloaded, coord));
                statusLabel.setText("Added " + coord
                        + (downloaded ? " (already downloaded)." : " (not downloaded yet)."));
            } else {
                statusLabel.setText(coord + " is already in the list.");
            }
            coordInput.setText("");
        });

        removeMavenBtn.addListener(SWT.Selection, e -> {
            int[] indices = mavenList.getSelectionIndices();
            if (indices != null && indices.length > 0) {
                for (int i = indices.length - 1; i >= 0; i--) {
                    mavenCoordinates.remove(indices[i]);
                }
                mavenList.remove(indices);
                statusLabel.setText("Removed " + indices.length + " Maven coordinate(s).");
            }
        });

        // Double-click on a Maven entry removes it from the list and puts it in the edit box
        mavenList.addListener(SWT.DefaultSelection, e -> {
            int idx = mavenList.getSelectionIndex();
            if (idx >= 0 && idx < mavenCoordinates.size()) {
                String coord = mavenCoordinates.remove(idx);
                mavenList.remove(idx);
                coordInput.setText(coord);
                coordInput.setFocus();
                coordInput.setSelection(coord.length());
                statusLabel.setText("Editing " + coord + " ...");
            }
        });

        downloadMavenBtn.addListener(SWT.Selection, e -> {
            int[] selectedIndices = mavenList.getSelectionIndices();
            if (selectedIndices == null || selectedIndices.length == 0) {
                showError(dialog, "No Selection",
                        "Please select one or more Maven dependencies in the list to download.");
                return;
            }
            List<String> coordsToDownload = new ArrayList<>();
            for (int idx : selectedIndices) {
                if (idx >= 0 && idx < mavenCoordinates.size()) {
                    coordsToDownload.add(mavenCoordinates.get(idx));
                }
            }
            if (coordsToDownload.isEmpty()) {
                return;
            }

            downloadMavenBtn.setEnabled(false);
            addMavenBtn.setEnabled(false);
            removeMavenBtn.setEnabled(false);
            statusLabel.setText("Downloading " + String.join(", ", coordsToDownload) + " ...");
            Display display = dialog.getDisplay();

            Thread resolverThread = new Thread(() -> {
                try {
                    List<String> jars = mavenResolver.resolveCoordinates(coordsToDownload);
                    display.asyncExec(() -> {
                        if (dialog.isDisposed()) {
                            return;
                        }
                        int addedCount = 0;
                        for (String jar : jars) {
                            if (!classpathEntries.contains(jar)) {
                                classpathEntries.add(jar);
                                addedCount++;
                            }
                        }
                        refreshMavenList(mavenList);
                        refreshClasspathList(cpList);
                        statusLabel.setText(
                                "Downloaded " + coordsToDownload.size() + " coordinate(s) -> "
                                        + jars.size() + " JAR(s) (" + addedCount + " new).");
                        downloadMavenBtn.setEnabled(true);
                        addMavenBtn.setEnabled(true);
                        removeMavenBtn.setEnabled(true);
                    });
                } catch (Exception ex) {
                    display.asyncExec(() -> {
                        if (dialog.isDisposed()) {
                            return;
                        }
                        statusLabel.setText("Failed to download selected Maven dependency.");
                        downloadMavenBtn.setEnabled(true);
                        addMavenBtn.setEnabled(true);
                        removeMavenBtn.setEnabled(true);
                        showError(dialog, "Maven Resolution Failed", ex.getMessage());
                    });
                }
            }, "maven-resolver-worker");
            resolverThread.setDaemon(true);
            resolverThread.start();
        });

        // Listeners for Group 2: Resolved Classpath Entries
        addJarBtn.addListener(SWT.Selection, e -> {
            FileDialog fd = new FileDialog(dialog, SWT.OPEN | SWT.MULTI);
            fd.setText("Select JAR File(s)");
            fd.setFilterExtensions(new String[] {"*.jar", "*.*"});
            String first = fd.open();
            if (first != null) {
                String filterPath = fd.getFilterPath();
                int added = 0;
                for (String name : fd.getFileNames()) {
                    String fullPath = filterPath + java.io.File.separator + name;
                    if (!classpathEntries.contains(fullPath)) {
                        classpathEntries.add(fullPath);
                        activeClasspathSet.add(fullPath);
                        added++;
                    }
                }
                refreshClasspathList(cpList);
                statusLabel.setText("Added " + added + " JAR(s) and marked in classpath.");
            }
        });

        addDirBtn.addListener(SWT.Selection, e -> {
            DirectoryDialog dd = new DirectoryDialog(dialog, SWT.OPEN);
            dd.setText("Select Class Folder");
            String dir = dd.open();
            if (dir != null) {
                if (!classpathEntries.contains(dir)) {
                    classpathEntries.add(dir);
                }
                activeClasspathSet.add(dir);
                refreshClasspathList(cpList);
                statusLabel.setText("Added directory and marked in classpath.");
            }
        });

        removeCpBtn.addListener(SWT.Selection, e -> {
            int[] indices = cpList.getSelectionIndices();
            if (indices != null && indices.length > 0) {
                for (int i = indices.length - 1; i >= 0; i--) {
                    String removed = classpathEntries.remove(indices[i]);
                    activeClasspathSet.remove(removed);
                }
                cpList.remove(indices);
                statusLabel.setText("Removed " + indices.length + " entry/entries from list.");
            }
        });

        addToCpBtn.addListener(SWT.Selection, e -> {
            int[] indices = cpList.getSelectionIndices();
            if (indices == null || indices.length == 0) {
                return;
            }
            for (int idx : indices) {
                if (idx >= 0 && idx < classpathEntries.size()) {
                    String path = classpathEntries.get(idx);
                    activeClasspathSet.add(path);
                    cpList.setItem(idx, formatListItem(true, path));
                }
            }
            cpList.setSelection(indices);
            statusLabel.setText(
                    "Marked " + indices.length + " entry/entries as in classpath ([✓]).");
        });

        removeFromCpBtn.addListener(SWT.Selection, e -> {
            int[] indices = cpList.getSelectionIndices();
            if (indices == null || indices.length == 0) {
                return;
            }
            for (int idx : indices) {
                if (idx >= 0 && idx < classpathEntries.size()) {
                    String path = classpathEntries.get(idx);
                    activeClasspathSet.remove(path);
                    cpList.setItem(idx, formatListItem(false, path));
                }
            }
            cpList.setSelection(indices);
            statusLabel.setText(
                    "Removed " + indices.length + " entry/entries from active classpath ([ ]).");
        });

        okBtn.addListener(SWT.Selection, e -> {
            confirmed = true;
            dialog.close();
        });

        cancelBtn.addListener(SWT.Selection, e -> {
            confirmed = false;
            dialog.close();
        });

        dialog.open();
        Display display = parent.getDisplay();
        while (!dialog.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
        return confirmed;
    }

    private void refreshMavenList(org.eclipse.swt.widgets.List mavenList) {
        int[] prevSelection = mavenList.getSelectionIndices();
        mavenList.removeAll();
        for (String coord : mavenCoordinates) {
            boolean downloaded = mavenResolver.isCoordinateDownloaded(coord);
            mavenList.add(formatListItem(downloaded, coord));
        }
        if (prevSelection != null && prevSelection.length > 0) {
            mavenList.setSelection(prevSelection);
        }
    }

    private void refreshClasspathList(org.eclipse.swt.widgets.List cpList) {
        int[] prevSelection = cpList.getSelectionIndices();
        cpList.removeAll();
        for (String entry : classpathEntries) {
            boolean active = activeClasspathSet.contains(entry);
            cpList.add(formatListItem(active, entry));
        }
        if (prevSelection != null && prevSelection.length > 0) {
            cpList.setSelection(prevSelection);
        }
    }

    /** Returns all Maven coordinates in the first list. */
    public List<String> getMavenCoordinates() {
        return new ArrayList<>(new LinkedHashSet<>(mavenCoordinates));
    }

    /** Returns all resolved/local classpath entries in the second list (both active and inactive). */
    public List<String> getClasspathEntries() {
        return new ArrayList<>(new LinkedHashSet<>(classpathEntries));
    }

    /** Returns only the classpath entries marked as currently in the classpath (`[✓]`). */
    public List<String> getActiveClasspathEntries() {
        List<String> activeOrdered = new ArrayList<>();
        for (String entry : classpathEntries) {
            if (activeClasspathSet.contains(entry)) {
                activeOrdered.add(entry);
            }
        }
        return activeOrdered;
    }

    private static void showError(Shell shell, String title, String message) {
        MessageBox mb = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
        mb.setText(title);
        mb.setMessage(message != null ? message : "Unknown error");
        mb.open();
    }
}
