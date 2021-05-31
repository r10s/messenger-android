package org.thoughtcrime.securesms.connect;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.b44t.messenger.DcContext;

import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.ConversationListActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.WelcomeActivity;
import org.thoughtcrime.securesms.util.task.ProgressDialogAsyncTask;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

public class AccountManager {

    private static final String TAG = AccountManager.class.getSimpleName();
    private static AccountManager self;

    private final static String DEFAULT_DB_NAME = "messenger.db";

    public class Account {
        private String dbName;
        private String displayname;
        private String addr;
        private boolean configured;
        private boolean current;

        public String getDescr() {
            String ret = "";
            if (!displayname.isEmpty() && !addr.isEmpty()) {
                ret = String.format("%s (%s)", displayname, addr);
            } else if (!addr.isEmpty()) {
                ret = addr;
            } else {
                ret = dbName;
            }
            if (!configured) {
                ret += " (not configured)";
            }
            return ret;
        }

        public boolean isCurrent() {
            return current;
        }

        public String getDbName() {
            return dbName;
        }
    };

    private @Nullable Account maybeGetAccount(File file) {
        try {
            if (!file.isDirectory() && file.getName().startsWith("messenger") && file.getName().endsWith(".db")) {
                DcContext testContext = new DcContext(null, file.getAbsolutePath());
                if (testContext.isOk()) {
                    Account ret = new Account();
                    ret.dbName = file.getName();
                    ret.displayname = testContext.getConfig("displayname");
                    ret.addr = testContext.getConfig("addr");
                    ret.configured = testContext.isConfigured() != 0;
                    return ret;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private File getUniqueDbName(Context context) {
        File dir = context.getFilesDir();
        int index = 1;
        while (true) {
            File test = new File(dir, String.format("messenger-%d.db", index));
            File testBlobdir = new File(dir, String.format("messenger-%d.db-blobs", index));
            if (!test.exists() && !testBlobdir.exists()) {
                return test;
            }
            index++;
        }
    }

    private void resetDcContext(Context context) {
        // create an empty DcContext object - this will be set up then, starting with
        // getSelectedAccount()
        ApplicationContext appContext = (ApplicationContext)context.getApplicationContext();
        appContext.dcContext.notificationCenter.removeAllNotifiations();
        appContext.dcContext.stopIo();
        appContext.dcContext.unref();
        appContext.dcContext = new ApplicationDcContext(context);
        appContext.dcContext.setStockTranslations();
        DirectShareUtil.resetAllShortcuts(appContext);
    }


    // public api

    public static AccountManager getInstance() {
        if (self == null) {
            self = new AccountManager();
        }
        return self;
    }

    public ArrayList<Account> getAccounts(Context context) {
        ArrayList<Account> result = new ArrayList<>();

        String dbName = PreferenceManager.getDefaultSharedPreferences(context)
                .getString("curr_account_db_name", DEFAULT_DB_NAME);

        try {
            File dir = context.getFilesDir();
            File[] files = dir.listFiles();
            for (File file : files) {
                Account account = maybeGetAccount(file);
                if (account!=null) {
                    if (account.dbName.equals(dbName)) {
                        account.current = true;
                    }
                    result.add(account);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    public void switchAccount(Context context, Account account) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        String prevDbName = sharedPreferences.getString("curr_account_db_name", DEFAULT_DB_NAME);

        // we remember prev_account_db_name in case the account is not configured
        // and the user want to rollback to the working account
        sharedPreferences.edit().putString("prev_account_db_name", prevDbName).apply();
        sharedPreferences.edit().putString("curr_account_db_name", account.dbName).apply();
        resetDcContext(context);
    }

    public File getSelectedAccount(Context context) {
        String dbName = PreferenceManager.getDefaultSharedPreferences(context)
                .getString("curr_account_db_name", DEFAULT_DB_NAME);
        return new File(context.getFilesDir(), dbName);
    }


    // add accounts

    private void beginAccountCreation(Context context) {
        // pause the current account and let the user create a new one.
        // this function is not needed on the very first account creation.
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        String prevDbName = sharedPreferences.getString("curr_account_db_name", DEFAULT_DB_NAME);
        String inCreationDbName = getUniqueDbName(context).getName();

        sharedPreferences.edit().putString("prev_account_db_name", prevDbName).apply();
        sharedPreferences.edit().putString("curr_account_db_name", inCreationDbName).apply();

        resetDcContext(context);
    }

    public boolean canRollbackAccountCreation(Context context) {
        String prevDbName = PreferenceManager.getDefaultSharedPreferences(context)
                .getString("prev_account_db_name", "");
        return !prevDbName.isEmpty();
    }

    public void rollbackAccountCreation(Activity activity) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity);
        String prevDbName = sharedPreferences.getString("prev_account_db_name", "");
        String inCreationDbName = sharedPreferences.getString("curr_account_db_name", "");

        Account prevAccount = maybeGetAccount(new File(activity.getFilesDir(), prevDbName));
        if (prevAccount!=null) {
            new SwitchAccountAsyncTask(activity, R.string.switching_account, prevAccount, inCreationDbName, null).execute();
        }
    }


    // delete account

    private void deleteAccount(Context context, String dbName) {
        try {
            File blobdir = new File(context.getFilesDir(), dbName+"-blobs");
            String [] blobfiles = blobdir.list();
            for (String blobfile: blobfiles) {
                new File(blobdir, blobfile).delete();
            }
            blobdir.delete();

            File dbFile = new File(context.getFilesDir(), dbName);
            dbFile.delete();
        } catch(Exception e) {
            e.printStackTrace();
        }

        File test = new File(context.getFilesDir(), dbName);
        if (test.exists()) {
            String err = String.format("Cannot delete account %s", test.getAbsolutePath());
            Log.w(TAG, err);
            Toast.makeText(context, err, Toast.LENGTH_LONG).show();
        }
    }


    // helper class for switching accounts gracefully

    private static class SwitchAccountAsyncTask extends ProgressDialogAsyncTask<Void, Void, Void> {
        private final WeakReference<Activity> activityWeakReference;
        private final @Nullable Account destAccount; // null creates a new account
        private final @Nullable String deleteDbName;
        private final @Nullable String qrAccount;

        public SwitchAccountAsyncTask(Activity activity, int title, @Nullable Account destAccount, @Nullable String deleteDbName, @Nullable String qrAccount) {
            super(activity, null, activity.getString(title));
            this.activityWeakReference = new WeakReference<>(activity);
            this.destAccount = destAccount;
            this.deleteDbName = deleteDbName;
            this.qrAccount = qrAccount;
        }
        @Override
        protected Void doInBackground(Void... voids) {
            Activity activity = activityWeakReference.get();
            if (activity!=null) {
                if (destAccount==null) {
                    AccountManager.getInstance().beginAccountCreation(activity);
                } else {
                    AccountManager.getInstance().switchAccount(activity, destAccount);

                    if (deleteDbName!=null) {
                        // used to delete the previous account, however, as a resilience check, make sure,
                        // we do not delete already configured accounts (just in case sth. changes the flow of activities)
                        DcContext testContext = new DcContext(null, new File(activity.getFilesDir(), deleteDbName).getAbsolutePath());
                        if (testContext.isOk()) {
                            if (testContext.isConfigured() == 0) {
                                testContext.unref();
                                AccountManager.getInstance().deleteAccount(activity, deleteDbName);
                                PreferenceManager.getDefaultSharedPreferences(activity)
                                        .edit().putString("prev_account_db_name", "").apply();
                            }
                        }
                    }
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            Activity activity = activityWeakReference.get();
            if (activity!=null) {
                activity.finishAffinity();
                if (destAccount==null) {
                    Intent intent = new Intent(activity, WelcomeActivity.class);
                    if (qrAccount!=null) {
                        intent.putExtra(WelcomeActivity.QR_ACCOUNT_EXTRA, qrAccount);
                    }
                    activity.startActivity(intent);
                } else {
                    activity.startActivity(new Intent(activity.getApplicationContext(), ConversationListActivity.class));
                }
            }
        }
    }

    // ui

    public void showSwitchAccountMenu(Activity activity) {
        ArrayList<AccountManager.Account> accounts = getAccounts(activity);

        // build menu
        int presel = 0;
        ArrayList<String> menu = new ArrayList<>();
        for (int i = 0; i < accounts.size(); i++) {
            AccountManager.Account account = accounts.get(i);
            if (account.isCurrent()) {
                presel = i;
            }
            menu.add(account.getDescr());
        }

        int addAccount = menu.size();
        menu.add(activity.getString(R.string.add_account));

        // show dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.switch_account)
                .setNegativeButton(R.string.cancel, null)
                .setSingleChoiceItems(menu.toArray(new String[menu.size()]), presel, (dialog, which) -> {
                    dialog.dismiss();
                    if (which==addAccount) {
                        new SwitchAccountAsyncTask(activity, R.string.one_moment, null, null, null).execute();
                    } else { // switch account
                        Account account = accounts.get(which);
                        if (!account.isCurrent()) {
                            new SwitchAccountAsyncTask(activity, R.string.switching_account, account, null, null).execute();
                        }
                    }
                });
        if (accounts.size() > 1) {
            builder.setNeutralButton(R.string.delete_account, (dialog, which) -> {
                showDeleteAccountMenu(activity);
            });
        }
        builder.show();
    }

    private void showDeleteAccountMenu(Activity activity) {
        ArrayList<AccountManager.Account> accounts = getAccounts(activity);

        ArrayList<String> menu = new ArrayList<>();
        for (AccountManager.Account account : accounts) {
            menu.add(account.getDescr());
        }
        int[] selection = {-1};
        new AlertDialog.Builder(activity)
                .setTitle(R.string.delete_account)
                .setSingleChoiceItems(menu.toArray(new String[menu.size()]), -1, (dialog, which) -> selection[0] = which)
                .setNegativeButton(R.string.cancel, (dialog, which) -> showSwitchAccountMenu(activity))
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    if (selection[0] >= 0 && selection[0] < accounts.size()) {
                        AccountManager.Account account = accounts.get(selection[0]);
                        if (account.isCurrent()) {
                            new AlertDialog.Builder(activity)
                                    .setMessage("To delete the currently active account, switch to another account first.")
                                    .setPositiveButton(R.string.ok, null)
                                    .show();
                        } else {
                            new AlertDialog.Builder(activity)
                                    .setTitle(account.getDescr())
                                    .setMessage(R.string.forget_login_confirmation_desktop)
                                    .setNegativeButton(R.string.cancel, (dialog2, which2) -> showSwitchAccountMenu(activity))
                                    .setPositiveButton(R.string.ok, (dialog2, which2) -> {
                                        deleteAccount(activity, account.getDbName());
                                        showSwitchAccountMenu(activity);
                                    })
                                    .show();
                        }
                    } else {
                        showDeleteAccountMenu(activity);
                    }
                })
                .show();
    }

    public void addAccountFromQr(Activity activity, String qr) {
        new SwitchAccountAsyncTask(activity, R.string.one_moment, null, null, qr).execute();
    }
}
