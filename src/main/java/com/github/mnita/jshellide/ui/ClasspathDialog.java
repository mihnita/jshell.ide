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
 * Dialog for configuring additional classpath entries (JARs, folders) and
 * resolving/downloading Maven dependencies (`groupId:artifactId:version`).
 */
public class ClasspathDialog {

    private final Shell parent;
    private final MavenDependencyResolver mavenResolver;
    private final List<String> classpathEntries;
    private final List<String> mavenCoordinates;
    private boolean confirmed = false;

    public ClasspathDialog(
            Shell parent,
            MavenDependencyResolver mavenResolver,
            List<String> currentClasspath,
            List<String> currentMavenCoords) {
        this.parent = parent;
        this.mavenResolver = mavenResolver;
        this.classpathEntries = new ArrayList<>(currentClasspath);
        this.mavenCoordinates = new ArrayList<>(currentMavenCoords);
    }

    /** Opens the modal dialog and returns {@code true} if the user clicked Apply/OK. */
    public boolean open() {
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        dialog.setText("Classpath & Maven Dependencies");
        dialog.setLayout(new GridLayout(1, false));
        dialog.setSize(680, 520);

        // Group 1: Maven Dependencies
        Group mavenGroup = new Group(dialog, SWT.NONE);
        mavenGroup.setText("Maven Dependencies (groupId:artifactId:version)");
        mavenGroup.setLayout(new GridLayout(3, false));
        mavenGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Text coordInput = new Text(mavenGroup, SWT.BORDER);
        coordInput.setMessage("e.g. com.google.code.gson:gson:2.11.0");
        coordInput.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button addMavenBtn = new Button(mavenGroup, SWT.PUSH);
        addMavenBtn.setText("Download && Add");

        Button removeMavenBtn = new Button(mavenGroup, SWT.PUSH);
        removeMavenBtn.setText("Remove Selected");

        org.eclipse.swt.widgets.List mavenList =
                new org.eclipse.swt.widgets.List(
                        mavenGroup, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        GridData mavenListData = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1);
        mavenListData.heightHint = 100;
        mavenList.setLayoutData(mavenListData);
        for (String coord : mavenCoordinates) {
            mavenList.add(coord);
        }

        // Group 2: Active Classpath Entries (JARs & Folders)
        Group cpGroup = new Group(dialog, SWT.NONE);
        cpGroup.setText("Resolved Classpath Entries (JARs & Class Directories)");
        cpGroup.setLayout(new GridLayout(3, false));
        cpGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        org.eclipse.swt.widgets.List cpList =
                new org.eclipse.swt.widgets.List(
                        cpGroup, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        GridData cpListData = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1);
        cpListData.heightHint = 150;
        cpList.setLayoutData(cpListData);
        for (String entry : classpathEntries) {
            cpList.add(entry);
        }

        Button addJarBtn = new Button(cpGroup, SWT.PUSH);
        addJarBtn.setText("Add JAR(s)...");

        Button addDirBtn = new Button(cpGroup, SWT.PUSH);
        addDirBtn.setText("Add Directory...");

        Button removeCpBtn = new Button(cpGroup, SWT.PUSH);
        removeCpBtn.setText("Remove Selected");

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

        // Listeners
        addMavenBtn.addListener(SWT.Selection, e -> {
            String coord = coordInput.getText().trim();
            if (!MavenDependencyResolver.isValidCoordinate(coord)) {
                showError(dialog, "Invalid Coordinate",
                        "Please enter a valid Maven coordinate in the form groupId:artifactId:version.");
                return;
            }
            addMavenBtn.setEnabled(false);
            statusLabel.setText("Resolving " + coord + " ...");
            Display display = dialog.getDisplay();

            Thread resolverThread = new Thread(() -> {
                try {
                    List<String> jars = mavenResolver.resolveCoordinate(coord);
                    display.asyncExec(() -> {
                        if (dialog.isDisposed()) {
                            return;
                        }
                        if (!mavenCoordinates.contains(coord)) {
                            mavenCoordinates.add(coord);
                            mavenList.add(coord);
                        }
                        int addedCount = 0;
                        for (String jar : jars) {
                            if (!classpathEntries.contains(jar)) {
                                classpathEntries.add(jar);
                                cpList.add(jar);
                                addedCount++;
                            }
                        }
                        coordInput.setText("");
                        statusLabel.setText(
                                "Resolved " + coord + " (" + jars.size() + " JARs, "
                                        + addedCount + " new).");
                        addMavenBtn.setEnabled(true);
                    });
                } catch (Exception ex) {
                    display.asyncExec(() -> {
                        if (dialog.isDisposed()) {
                            return;
                        }
                        statusLabel.setText("Failed to resolve " + coord);
                        addMavenBtn.setEnabled(true);
                        showError(dialog, "Maven Resolution Failed", ex.getMessage());
                    });
                }
            }, "maven-resolver-worker");
            resolverThread.setDaemon(true);
            resolverThread.start();
        });

        removeMavenBtn.addListener(SWT.Selection, e -> {
            int idx = mavenList.getSelectionIndex();
            if (idx >= 0) {
                mavenCoordinates.remove(idx);
                mavenList.remove(idx);
            }
        });

        addJarBtn.addListener(SWT.Selection, e -> {
            FileDialog fd = new FileDialog(dialog, SWT.OPEN | SWT.MULTI);
            fd.setText("Select JAR File(s)");
            fd.setFilterExtensions(new String[] {"*.jar", "*.*"});
            String first = fd.open();
            if (first != null) {
                String filterPath = fd.getFilterPath();
                for (String name : fd.getFileNames()) {
                    String fullPath = filterPath + java.io.File.separator + name;
                    if (!classpathEntries.contains(fullPath)) {
                        classpathEntries.add(fullPath);
                        cpList.add(fullPath);
                    }
                }
            }
        });

        addDirBtn.addListener(SWT.Selection, e -> {
            DirectoryDialog dd = new DirectoryDialog(dialog, SWT.OPEN);
            dd.setText("Select Class Folder");
            String dir = dd.open();
            if (dir != null && !classpathEntries.contains(dir)) {
                classpathEntries.add(dir);
                cpList.add(dir);
            }
        });

        removeCpBtn.addListener(SWT.Selection, e -> {
            int[] indices = cpList.getSelectionIndices();
            if (indices != null && indices.length > 0) {
                for (int i = indices.length - 1; i >= 0; i--) {
                    classpathEntries.remove(indices[i]);
                }
                cpList.remove(indices);
            }
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

    public List<String> getClasspathEntries() {
        Set<String> unique = new LinkedHashSet<>(classpathEntries);
        return new ArrayList<>(unique);
    }

    public List<String> getMavenCoordinates() {
        Set<String> unique = new LinkedHashSet<>(mavenCoordinates);
        return new ArrayList<>(unique);
    }

    private static void showError(Shell shell, String title, String message) {
        MessageBox mb = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
        mb.setText(title);
        mb.setMessage(message != null ? message : "Unknown error");
        mb.open();
    }
}
