/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.creator.controller;

import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.skcraft.concurrency.Deferred;
import com.skcraft.concurrency.Deferreds;
import com.skcraft.concurrency.SettableProgress;
import com.skcraft.launcher.creator.controller.task.DirectoryWalker;
import com.skcraft.launcher.creator.dialog.AddFromUrlDialog;
import com.skcraft.launcher.creator.dialog.VersionCheckDialog;
import com.skcraft.launcher.creator.model.creator.ModFile;
import com.skcraft.launcher.creator.model.swing.ModFileTableModel;
import com.skcraft.launcher.creator.util.ModInfoReader;
import com.skcraft.launcher.creator.util.ModInfoReader.ModInfo;
import com.skcraft.launcher.creator.util.NemModList;
import com.skcraft.launcher.creator.util.NemModList.ModEntry;
import com.skcraft.launcher.dialog.ProgressDialog;
import com.skcraft.launcher.swing.SwingHelper;
import com.skcraft.launcher.util.HttpRequest;
import com.skcraft.launcher.util.SwingExecutor;

import javax.annotation.Nullable;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

public class VersionCheckController{

    private final VersionCheckDialog dialog;
    private Frame frame;
    private File modDir;
    private AddFromUrlDialog addDialog;
    private final ListeningExecutorService executor;
    private List<ModFile> mods;

    public VersionCheckController(VersionCheckDialog dialog, Frame frame, ListeningExecutorService executor){
        this.dialog = dialog;
        this.frame = frame;
        this.executor = executor;
    }

    public void showUpdates(File dir, String gameVersion, Window parentWindow){
        initListeners();

        this.modDir = dir;

        DirectoryWalker walker = new DirectoryWalker(dir);
        walker.setRecursive(false);
        walker.setFileFilter(pathname -> pathname.getName().endsWith(".jar"));

        ModInfoReader binaryInspector = new ModInfoReader();
        NemModList nemModList = new NemModList();

        SettableProgress progress = new SettableProgress("Retrieving mod information...", -1);

        Deferred<?> deferred = Deferreds.makeDeferred(executor.submit(walker), executor)
                .thenTap(() -> progress.set("Querying NotEnoughMods for version data...", -1))
                .thenTap(() -> {
                    try{
                        nemModList.load(gameVersion);
                    }catch(IOException | InterruptedException e){
                        throw new RuntimeException("Failed to retrieve mod information from NotEnoughMods. Perhaps NEM doesn't support your pack's MC version.", e);
                    }
                })
                .thenTap(() -> progress.set("Scanning mod files for manifests...", -1))
                .thenApply(files -> {
                    List<ModFile> mods = Lists.newArrayList();

                    for(File file : files){
                        ModFile mod = new ModFile();
                        mod.setGameVersion(gameVersion);
                        mod.setFile(file);

                        List<? extends ModInfo> infoList = binaryInspector.detectMods(file);
                        if(!infoList.isEmpty()){
                            ModInfo info = infoList.get(0);
                            mod.setModId(info.getModId());
                            mod.setName(info.getName());
                            mod.setVersion(info.getVersion());

                            if(info.getUrl() != null){
                                mod.setUrl(getFirstUrl(info.getUrl(), "http://" + info.getUrl()));
                            }
                        }

                        if(mod.getModId() != null){
                            ModEntry entry = nemModList.get(mod.getModId());

                            if(entry != null){
                                mod.setLatestVersion(entry.getLatestVersion());
                                mod.setLatestDevVersion(entry.getLatestDevVersion());
                                if(entry.getUrl() != null){
                                    mod.setUrl(entry.getUrl());
                                }
                            }
                        }

                        mods.add(mod);
                    }

                    return mods;
                })
                .handleAsync(mods -> {
                    this.mods = mods;
                    List<ModFile> known = Lists.newArrayList();
                    List<ModFile> unknown = Lists.newArrayList();

                    for(ModFile mod : mods){
                        if(mod.getVersion() != null && mod.getLatestVersion() != null){
                            if(!mod.getCleanVersion().equals(mod.getLatestVersion()) && !mod.getCleanVersion().equals(mod.getLatestDevVersion())){
                                known.add(mod);
                            }
                        }else{
                            unknown.add(mod);
                        }
                    }

                    dialog.getKnownModsTable().setModel(new ModFileTableModel(known));
                    dialog.getUnknownModsTable().setModel(new ModFileTableModel(unknown));
                    dialog.getKnownModsTable().getRowSorter().toggleSortOrder(1);
                    dialog.getUnknownModsTable().getRowSorter().toggleSortOrder(1);
                    dialog.setVisible(true);
                }, ex -> {
                }, SwingExecutor.INSTANCE);

        ProgressDialog.showProgress(parentWindow, deferred, progress, "Checking for mod updates...", "Checking for mod updates...");
        SwingHelper.addErrorDialogCallback(parentWindow, deferred);
    }

    private void initListeners(){
        dialog.getCloseButton().addActionListener(e -> dialog.dispose());

        ModTableMouseListener mouseListener = new ModTableMouseListener();
        dialog.getKnownModsTable().addMouseListener(mouseListener);
        dialog.getUnknownModsTable().addMouseListener(mouseListener);

        dialog.getAddModFromUrlButton().addActionListener(e -> {
            addDialog = new AddFromUrlDialog(frame);
            addDialog.getCancelButton().addActionListener(ev -> addDialog.dispose());
            addDialog.getAddButton().addActionListener(ev -> {
                if(modDir != null && !addDialog.getUrlField().getText().isEmpty()){
                    try{
                        File tmpfile = File.createTempFile("tempmod", null);
                        ModInfoReader binaryInspector = new ModInfoReader();

                        HttpRequest
                                .get(new URL(addDialog.getUrlField().getText()))
                                .execute()
                                .expectResponseCode(200)
                                .saveContent(tmpfile);

                        List<? extends ModInfo> infoList = binaryInspector.detectMods(tmpfile);
                        if(!infoList.isEmpty()){
                            ModInfo info = infoList.get(0);

                            if(info.getModId() != null && !info.getModId().isEmpty()){
                                String fileName = info.getModId();
                                if(info.getVersion() != null && !info.getVersion().isEmpty()){
                                    fileName += "-";
                                    fileName += info.getVersion();
                                }

                                File modFile = new File(modDir, fileName + ".jar");
                                File modUrlFile = new File(modDir, fileName + ".jar.url.txt");

                                Files.move(tmpfile, modFile);

                                PrintWriter modUrlFileWriter = new PrintWriter(modUrlFile, "UTF-8");
                                modUrlFileWriter.print(new URL(addDialog.getUrlField().getText()).toString());
                                modUrlFileWriter.close();

                                for(ModFile mod : this.mods){
                                    if(mod.getModId().equals(info.getModId())){
                                        if(!mod.getFile().delete()){
                                            throw new IOException("Could not delete old mod file " + tmpfile.getAbsolutePath());
                                        }
                                        File oldModUrlFile = new File(mod.getFile().getAbsolutePath() + ".url.txt");
                                        if(oldModUrlFile.exists()){
                                            if(!oldModUrlFile.delete()){
                                                throw new IOException("Could not delete old mod file " + tmpfile.getAbsolutePath());
                                            }
                                        }
                                    }
                                }

                                addDialog.dispose();
                                return;
                            }
                        }

                        if(!tmpfile.delete()){
                            throw new IOException("Could not delete temporary file " + tmpfile.getAbsolutePath());
                        }

                    }catch(IOException | InterruptedException ex){
                        ex.printStackTrace();
                    }
                }
            });
            addDialog.setVisible(true);
        });
    }

    @Nullable
    private static URL getFirstUrl(String... options){
        for(String option : options){
            try{
                return new URL(option);
            }catch(MalformedURLException ignored){
            }
        }

        return null;
    }

    private class ModTableMouseListener extends MouseAdapter{

        public void mousePressed(MouseEvent e){
            if(e.getClickCount() == 2){
                JTable table = (JTable) e.getSource();
                Point point = e.getPoint();
                int selectedIndex = table.rowAtPoint(point);
                if(selectedIndex >= 0){
                    selectedIndex = table.convertRowIndexToModel(selectedIndex);
                    ModFile mod = ((ModFileTableModel) table.getModel()).getMod(selectedIndex);
                    if(mod != null && mod.getUrl() != null){
                        SwingHelper.openURL(mod.getUrl(), dialog);
                    }
                }
            }
        }

    }

}
