/*
 * Copyright (c) 1998-2020 by Richard A. Wilkes. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, version 2.0. If a copy of the MPL was not distributed with
 * this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, version 2.0.
 */

package com.trollworks.gcs.character;

import com.trollworks.gcs.advantage.Advantage;
import com.trollworks.gcs.equipment.Equipment;
import com.trollworks.gcs.feature.Bonus;
import com.trollworks.gcs.feature.Feature;
import com.trollworks.gcs.feature.LeveledAmount;
import com.trollworks.gcs.modifier.AdvantageModifier;
import com.trollworks.gcs.modifier.EquipmentModifier;
import com.trollworks.gcs.preferences.SheetPreferences;
import com.trollworks.gcs.skill.Skill;
import com.trollworks.gcs.skill.Technique;
import com.trollworks.gcs.spell.Spell;
import com.trollworks.gcs.spell.RitualMagicSpell;
import com.trollworks.gcs.widgets.outline.ListRow;
import com.trollworks.toolkit.utility.I18n;
import com.trollworks.toolkit.utility.Preferences;
import com.trollworks.toolkit.utility.notification.NotifierTarget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

/**
 * A thread for doing background updates of the prerequisite status of a character sheet.
 */
public class PrerequisitesThread extends Thread implements NotifierTarget {
    private static HashMap<GURPSCharacter, PrerequisitesThread> MAP = new HashMap<>();
    private static int                                          COUNTER;
    private        CharacterSheet                               mSheet;
    private        GURPSCharacter                               mCharacter;
    private        boolean                                      mNeedUpdate;
    private        boolean                                      mNeedRepaint;
    private        boolean                                      mIsProcessing;

    /**
     * @param character The character being processed.
     * @return The thread that does the processing.
     */
    public static PrerequisitesThread getThread(GURPSCharacter character) {
        synchronized (MAP) {
            return MAP.get(character);
        }
    }

    /**
     * Returns only when the prerequisites thread is idle.
     *
     * @param character The character to wait for.
     * @return The thread that does the processing.
     */
    public static PrerequisitesThread waitForProcessingToFinish(GURPSCharacter character) {
        PrerequisitesThread thread = getThread(character);
        if (thread != null && thread != Thread.currentThread()) {
            boolean checkAgain = true;
            while (checkAgain) {
                synchronized (thread) {
                    checkAgain = thread.mIsProcessing || thread.mNeedUpdate;
                }
                try {
                    sleep(200);
                } catch (Exception exception) {
                    // Ignore...
                }
            }
        }
        return thread;
    }

    /**
     * Creates a new prerequisites thread.
     *
     * @param sheet The sheet we're attached to.
     */
    public PrerequisitesThread(CharacterSheet sheet) {
        super("Prerequisites #" + ++COUNTER);
        setPriority(NORM_PRIORITY);
        setDaemon(true);
        mSheet = sheet;
        mCharacter = sheet.getCharacter();
        mNeedUpdate = true;
        mCharacter.addTarget(this, Profile.ID_TECH_LEVEL, GURPSCharacter.ID_STRENGTH, GURPSCharacter.ID_DEXTERITY, GURPSCharacter.ID_INTELLIGENCE, GURPSCharacter.ID_HEALTH, GURPSCharacter.ID_WILL, GURPSCharacter.ID_PERCEPTION, Spell.ID_NAME, Spell.ID_COLLEGE, Spell.ID_POINTS, Spell.ID_LIST_CHANGED, Skill.ID_NAME, Skill.ID_SPECIALIZATION, Skill.ID_LEVEL, Skill.ID_RELATIVE_LEVEL, Skill.ID_ENCUMBRANCE_PENALTY, Skill.ID_POINTS, Skill.ID_TECH_LEVEL, Skill.ID_LIST_CHANGED, Advantage.ID_NAME, Advantage.ID_LEVELS, Advantage.ID_LIST_CHANGED, Equipment.ID_EXTENDED_WEIGHT, Equipment.ID_EQUIPPED, Equipment.ID_QUANTITY, Equipment.ID_LIST_CHANGED, EquipmentModifier.ID_WEIGHT_ADJ, EquipmentModifier.ID_COST_ADJ);
        Preferences.getInstance().getNotifier().add(this, SheetPreferences.OPTIONAL_IQ_RULES_PREF_KEY, SheetPreferences.OPTIONAL_MODIFIER_RULES_PREF_KEY, SheetPreferences.OPTIONAL_STRENGTH_RULES_PREF_KEY, SheetPreferences.OPTIONAL_THRUST_DAMAGE_PREF_KEY);
        GURPSCharacter character = mCharacter;
        synchronized (MAP) {
            MAP.put(character, this);
        }
    }

    @Override
    public void run() {
        try {
            while (!mSheet.hasBeenDisposed()) {
                try {
                    boolean needUpdate;
                    synchronized (this) {
                        needUpdate = mNeedUpdate;
                        mNeedUpdate = false;
                        mIsProcessing = needUpdate;
                    }
                    if (needUpdate) {
                        processFeatures();
                        processRows(mCharacter.getAdvantagesIterator(false));
                        processRows(mCharacter.getSkillsIterator());
                        processRows(mCharacter.getSpellsIterator());
                        processRows(mCharacter.getEquipmentIterator());
                        if (mNeedRepaint) {
                            mSheet.repaint();
                        }
                        synchronized (this) {
                            mIsProcessing = false;
                        }
                    } else {
                        sleep(500);
                    }
                } catch (InterruptedException iEx) {
                    throw iEx;
                } catch (Exception exception) {
                    // Catch everything here so that manipulations to the character
                    // sheet that invalidate state don't stop our thread from
                    // continuing.
                    synchronized (this) {
                        mNeedUpdate = true;
                    }
                    if (mNeedRepaint) {
                        mSheet.repaint();
                    }
                    sleep(200);
                }
            }
        } catch (InterruptedException outerIEx) {
            // Someone is trying to terminate us... let them.
        }
        synchronized (this) {
            mNeedUpdate = mIsProcessing = false;
        }
        Preferences.getInstance().getNotifier().remove(this);
        GURPSCharacter character = mCharacter;
        synchronized (MAP) {
            MAP.remove(character);
        }
    }

    private void processFeatures() throws Exception {
        HashMap<String, ArrayList<Feature>> map = new HashMap<>();
        buildFeatureMap(map, mCharacter.getAdvantagesIterator(false));
        buildFeatureMap(map, mCharacter.getSkillsIterator());
        buildFeatureMap(map, mCharacter.getSpellsIterator());
        buildFeatureMap(map, mCharacter.getEquipmentIterator());
        mCharacter.setFeatureMap(map);
    }

    private void buildFeatureMap(HashMap<String, ArrayList<Feature>> map, Iterator<? extends ListRow> iterator) throws Exception {
        while (iterator.hasNext()) {
            ListRow row = iterator.next();
            if (row instanceof Equipment) {
                Equipment equipment = (Equipment) row;
                if (!equipment.isEquipped() || equipment.getQuantity() < 1) {
                    // Don't allow unequipped equipment to affect the character
                    continue;
                }
            }
            for (Feature feature : row.getFeatures()) {
                processFeature(map, row instanceof Advantage ? ((Advantage) row).getLevels() : 0, feature);
                if (feature instanceof Bonus) {
                    ((Bonus) feature).setParent(row);
                }
            }
            if (row instanceof Advantage) {
                Advantage advantage = (Advantage) row;
                for (Bonus bonus : advantage.getCRAdj().getBonuses(advantage.getCR())) {
                    processFeature(map, 0, bonus);
                    bonus.setParent(row);
                }
                for (AdvantageModifier modifier : advantage.getModifiers()) {
                    if (modifier.isEnabled()) {
                        for (Feature feature : modifier.getFeatures()) {
                            processFeature(map, modifier.getLevels(), feature);
                            if (feature instanceof Bonus) {
                                ((Bonus) feature).setParent(row);
                            }
                        }
                    }
                }
            }
            if (row instanceof Equipment) {
                Equipment equipment = (Equipment) row;
                for (EquipmentModifier modifier : equipment.getModifiers()) {
                    if (modifier.isEnabled()) {
                        for (Feature feature : modifier.getFeatures()) {
                            processFeature(map, 0, feature);
                            if (feature instanceof Bonus) {
                                ((Bonus) feature).setParent(row);
                            }
                        }
                    }
                }
            }
            checkIfUpdated();
        }
    }

    private void processFeature(HashMap<String, ArrayList<Feature>> map, int levels, Feature feature) {
        String             key  = feature.getKey().toLowerCase();
        ArrayList<Feature> list = map.get(key);
        if (list == null) {
            list = new ArrayList<>(1);
            map.put(key, list);
        }
        if (feature instanceof Bonus) {
            LeveledAmount amount = ((Bonus) feature).getAmount();
            if (amount.getLevel() != levels) {
                amount.setLevel(levels);
                mNeedRepaint = true;
            }
        }
        list.add(feature);
    }

    private void checkIfUpdated() throws Exception {
        boolean needUpdate;
        synchronized (this) {
            needUpdate = mNeedUpdate;
        }
        if (needUpdate || mSheet.hasBeenDisposed()) {
            throw new Exception();
        }
    }

    private void processRows(Iterator<? extends ListRow> iterator) throws Exception {
        StringBuilder builder = new StringBuilder();
        while (iterator.hasNext()) {
            ListRow row = iterator.next();
            builder.setLength(0);
            boolean satisfied = row.getPrereqs().satisfied(mCharacter, row, builder, "<li>");
            if (satisfied && row instanceof Technique) {
                satisfied = ((Technique) row).satisfied(builder, "<li>");
            }
            if (satisfied && row instanceof RitualMagicSpell) {
                satisfied = ((RitualMagicSpell) row).satisfied(builder, "<li>");
            }
            if (row.isSatisfied() != satisfied) {
                row.setSatisfied(satisfied);
                mNeedRepaint = true;
            }
            if (!satisfied) {
                builder.insert(0, "<html><body>" + I18n.Text("Reason:") + "<ul>");
                builder.append("</ul></body></html>");
                row.setReasonForUnsatisfied(builder.toString().replaceAll("<ul>", "<ul style='margin-top: 0; margin-bottom: 0;'>"));
            }
            checkIfUpdated();
        }
    }

    /** Marks an update request. */
    public synchronized void markForUpdate() {
        mNeedUpdate = true;
    }

    @Override
    public void handleNotification(Object producer, String type, Object data) {
        if (SheetPreferences.OPTIONAL_IQ_RULES_PREF_KEY.equals(type)) {
            mCharacter.updateWillAndPerceptionDueToOptionalIQRuleUseChange();
        } else if (SheetPreferences.OPTIONAL_MODIFIER_RULES_PREF_KEY.equals(type)) {
            mCharacter.notifySingle(Advantage.ID_LIST_CHANGED, null);
        } else if (SheetPreferences.OPTIONAL_STRENGTH_RULES_PREF_KEY.equals(type)) {
            mCharacter.notifySingle(type, data);
        } else if (SheetPreferences.OPTIONAL_THRUST_DAMAGE_PREF_KEY.equals(type)) {
            mCharacter.notifySingle(type, data);
        }
        markForUpdate();
    }

    @Override
    public int getNotificationPriority() {
        return 0;
    }
}
