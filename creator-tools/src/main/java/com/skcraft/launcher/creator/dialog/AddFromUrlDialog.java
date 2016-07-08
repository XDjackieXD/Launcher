package com.skcraft.launcher.creator.dialog;

import lombok.Getter;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * Created by Jakob aka XDjackieXD
 */
public class AddFromUrlDialog extends JDialog{

    @Getter
    private final JButton cancelButton = new JButton("Cancel");
    @Getter
    private final JButton addButton = new JButton("Add");
    @Getter
    private final JTextField urlField = new JTextField();

    public AddFromUrlDialog(Window parent){
        super(parent, "Add Mod from URL", ModalityType.DOCUMENT_MODAL);

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        initComponents();
        pack();
        setLocationRelativeTo(parent);
    }

    private void initComponents(){
        JPanel container = new JPanel();
        container.setLayout(new MigLayout("insets dialog, fill"));

        container.add(new JLabel("URL:"), "span");
        container.add(urlField , "span, grow");
        container.add(cancelButton, "tag cancel, sizegroup bttn");
        container.add(addButton, "tag add, sizegroup bttn");

        add(container, BorderLayout.CENTER);

        getRootPane().registerKeyboardAction(e -> cancelButton.doClick(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

}
