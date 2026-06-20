package com.example.officemasterbackup.ui;

import android.Manifest;
import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.CallLog;
import android.provider.MediaStore;
import android.provider.Telephony;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.example.officemasterbackup.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.mail.Address;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.InternetAddress;
import javax.mail.search.AndTerm;
import javax.mail.search.ComparisonTerm;
import javax.mail.search.ReceivedDateTerm;
import javax.mail.search.SearchTerm;

public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 1001;

    private static final String PREFS_NAME = "office_master_backup_prefs";
    private static final String KEY_GMAIL_EMAIL = "gmail_email";
    private static final String KEY_GMAIL_PASSWORD = "gmail_app_password";

    private static final int GMAIL_LIMIT = 100;
    private static final int GMAIL_TOTAL_TIMEOUT_SECONDS = 45;

    private TextView statusText;
    private TextView selectedRangeText;

    private Button backupButton;
    private Button startDateButton;
    private Button endDateButton;

    private EditText gmailEmailInput;
    private EditText gmailPasswordInput;

    private Calendar startCalendar;
    private Calendar endCalendar;
    private boolean endDateManuallySelected = false;

    private String currentStamp;

    private final String[] requiredPermissions = new String[]{
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS
    };

    private final SimpleDateFormat uiDateFormat =
            new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

    private final SimpleDateFormat fileDateFormat =
            new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    private final SimpleDateFormat readableDateFormat =
            new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        backupButton = findViewById(R.id.backupButton);
        startDateButton = findViewById(R.id.startDateButton);
        endDateButton = findViewById(R.id.endDateButton);
        selectedRangeText = findViewById(R.id.selectedRangeText);

        gmailEmailInput = findViewById(R.id.gmailEmailInput);
        gmailPasswordInput = findViewById(R.id.gmailPasswordInput);

        makeAppPasswordVisible();
        setupDefaultDateRange();
        loadSavedGmailDetails();
        updateDateViews();
        updatePermissionStatus();

        startDateButton.setOnClickListener(v -> showStartDatePicker());
        endDateButton.setOnClickListener(v -> showEndDatePicker());

        backupButton.setOnClickListener(v -> {
            if (!hasAllPermissions()) {
                requestPermissions(requiredPermissions, PERMISSION_REQUEST_CODE);
                return;
            }

            String gmailEmail = gmailEmailInput.getText().toString().trim();
            String gmailAppPassword = gmailPasswordInput.getText().toString().replace(" ", "").trim();

            if (gmailEmail.isEmpty() || gmailAppPassword.isEmpty()) {
                statusText.setText("First time only: enter Gmail address and Gmail App Password.");
                return;
            }

            long startMillis = getStartOfDayMillis(startCalendar);
            long endMillis = getEndOfDayMillis(endCalendar);

            if (endMillis < startMillis) {
                statusText.setText("End Date cannot be before Start Date. Please select End Date again.");
                return;
            }

            saveGmailDetails(gmailEmail, gmailAppPassword);

            backupButton.setEnabled(false);
            statusText.setText(
                    "Starting backup...\n" +
                            "Date range: " + uiDateFormat.format(startCalendar.getTime()) +
                            " to " + uiDateFormat.format(endCalendar.getTime()) + "\n\n" +
                            "Reading call logs, SMS and Gmail..."
            );

            new Thread(() -> backupCallsSmsGmailAndZip(
                    gmailEmail,
                    gmailAppPassword,
                    startMillis,
                    endMillis
            )).start();
        });
    }

    private void setupDefaultDateRange() {
        endCalendar = Calendar.getInstance();

        startCalendar = Calendar.getInstance();
        startCalendar.add(Calendar.DAY_OF_YEAR, -6);

        endDateManuallySelected = false;
    }

    private void showStartDatePicker() {
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    startCalendar.set(Calendar.YEAR, year);
                    startCalendar.set(Calendar.MONTH, month);
                    startCalendar.set(Calendar.DAY_OF_MONTH, dayOfMonth);

                    if (!endDateManuallySelected) {
                        endCalendar = Calendar.getInstance();
                    }

                    updateDateViews();
                },
                startCalendar.get(Calendar.YEAR),
                startCalendar.get(Calendar.MONTH),
                startCalendar.get(Calendar.DAY_OF_MONTH)
        );

        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        dialog.show();
    }

    private void showEndDatePicker() {
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    endCalendar.set(Calendar.YEAR, year);
                    endCalendar.set(Calendar.MONTH, month);
                    endCalendar.set(Calendar.DAY_OF_MONTH, dayOfMonth);

                    endDateManuallySelected = true;
                    updateDateViews();
                },
                endCalendar.get(Calendar.YEAR),
                endCalendar.get(Calendar.MONTH),
                endCalendar.get(Calendar.DAY_OF_MONTH)
        );

        dialog.getDatePicker().setMinDate(getStartOfDayMillis(startCalendar));
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        dialog.show();
    }

    private void updateDateViews() {
        String startText = uiDateFormat.format(startCalendar.getTime());
        String endText = uiDateFormat.format(endCalendar.getTime());

        startDateButton.setText("Start Date: " + startText);
        endDateButton.setText("End Date: " + endText);

        String endMode = endDateManuallySelected
                ? "End date manually selected"
                : "End date auto set to today";

        selectedRangeText.setText(
                "Selected Range:\n" +
                        startText + " to " + endText + "\n\n" +
                        endMode
        );
    }

    private long getStartOfDayMillis(Calendar source) {
        Calendar calendar = (Calendar) source.clone();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private long getEndOfDayMillis(Calendar source) {
        Calendar calendar = (Calendar) source.clone();
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTimeInMillis();
    }

    private void makeAppPasswordVisible() {
        gmailPasswordInput.setInputType(
                InputType.TYPE_CLASS_TEXT |
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        );
        gmailPasswordInput.setTransformationMethod(null);
    }

    private void loadSavedGmailDetails() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        String savedEmail = prefs.getString(KEY_GMAIL_EMAIL, "");
        String savedPassword = prefs.getString(KEY_GMAIL_PASSWORD, "");

        gmailEmailInput.setText(savedEmail);
        gmailPasswordInput.setText(savedPassword);
    }

    private void saveGmailDetails(String gmailEmail, String gmailAppPassword) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_GMAIL_EMAIL, gmailEmail)
                .putString(KEY_GMAIL_PASSWORD, gmailAppPassword)
                .apply();
    }

    private boolean hasAllPermissions() {
        for (String permission : requiredPermissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void updatePermissionStatus() {
        if (hasAllPermissions()) {
            statusText.setText("Ready. Default backup range is last 7 days.");
        } else {
            statusText.setText("Permissions needed. Tap Backup Now and allow Call Log + SMS access.");
        }
    }

    private void setStatusFromThread(String message) {
        runOnUiThread(() -> statusText.setText(message));
    }

    private void backupCallsSmsGmailAndZip(
            String gmailEmail,
            String gmailAppPassword,
            long startMillis,
            long endMillis
    ) {
        try {
            currentStamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(new Date());

            String startDateText = fileDateFormat.format(new Date(startMillis));
            String endDateText = fileDateFormat.format(new Date(endMillis));

            File officeBackupDir = new File(getExternalFilesDir(null), "OfficeBackup");
            deleteFolderContents(officeBackupDir);
            officeBackupDir.mkdirs();

            setStatusFromThread("Reading call logs...\n" + startDateText + " to " + endDateText);
            BackupResult callResult = backupCallLogs(startMillis, endMillis);

            setStatusFromThread("Reading SMS...\n" + startDateText + " to " + endDateText);
            BackupResult smsResult = backupSms(startMillis, endMillis);

            setStatusFromThread("Connecting to Gmail...\n" + startDateText + " to " + endDateText);
            GmailAttempt gmailAttempt = backupGmailWithTimeout(gmailEmail, gmailAppPassword, startMillis, endMillis);

            setStatusFromThread("Creating final ZIP...");
            File zipFile = createZipFile(officeBackupDir);

            setStatusFromThread("Saving ZIP to Downloads...");
            String savedLocation = saveZipToDownloads(zipFile);

            runOnUiThread(() -> {
                backupButton.setEnabled(true);
                statusText.setText(
                        "Backup ZIP created successfully.\n\n" +
                                "Date range:\n" + startDateText + " to " + endDateText + "\n\n" +
                                "Call logs saved: " + callResult.count + "\n" +
                                "SMS saved: " + smsResult.count + "\n" +
                                "Gmail saved: " + gmailAttempt.result.count + "\n\n" +
                                "ZIP file:\n" + zipFile.getName() + "\n\n" +
                                "Saved to:\n" + savedLocation +
                                gmailAttempt.note
                );
            });

        } catch (Exception e) {
            runOnUiThread(() -> {
                backupButton.setEnabled(true);
                statusText.setText("Backup failed:\n" + e.getMessage());
            });
        }
    }

    private GmailAttempt backupGmailWithTimeout(
            String gmailEmail,
            String gmailAppPassword,
            long startMillis,
            long endMillis
    ) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<BackupResult> future = executor.submit(
                () -> backupGmail(gmailEmail, gmailAppPassword, startMillis, endMillis)
        );

        try {
            BackupResult result = future.get(GMAIL_TOTAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return new GmailAttempt(result, "");

        } catch (TimeoutException e) {
            future.cancel(true);

            String errorText =
                    "Gmail backup timed out after " + GMAIL_TOTAL_TIMEOUT_SECONDS + " seconds.\n" +
                            "ZIP still created with Call Logs + SMS.\n" +
                            "Check internet speed, Gmail App Password, and Gmail IMAP setting.";

            BackupResult errorResult = writeGmailErrorFile(gmailEmail, errorText);

            return new GmailAttempt(
                    errorResult,
                    "\n\nGmail backup timed out, but ZIP was still created.\nCheck Gmail error file inside ZIP."
            );

        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String errorText = cause == null ? e.toString() : cause.getMessage();

            if (errorText == null || errorText.trim().isEmpty()) {
                errorText = cause == null ? e.toString() : cause.toString();
            }

            BackupResult errorResult = writeGmailErrorFile(gmailEmail, errorText);

            return new GmailAttempt(
                    errorResult,
                    "\n\nGmail backup failed, but ZIP was still created.\nError: " + errorText
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            String errorText = "Gmail backup interrupted.";
            BackupResult errorResult = writeGmailErrorFile(gmailEmail, errorText);

            return new GmailAttempt(
                    errorResult,
                    "\n\nGmail backup interrupted, but ZIP was still created."
            );

        } finally {
            executor.shutdownNow();
        }
    }

    private BackupResult backupCallLogs(long startMillis, long endMillis) throws Exception {
        Cursor cursor = null;

        JSONArray jsonArray = new JSONArray();
        StringBuilder readable = new StringBuilder();

        readable.append("CALL LOG READABLE SUMMARY\n");
        readable.append("============================================================\n\n");
        readable.append("Date range: ")
                .append(readableDateFormat.format(new Date(startMillis)))
                .append(" to ")
                .append(readableDateFormat.format(new Date(endMillis)))
                .append("\n\n");

        String[] projection = new String[]{
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
        };

        String selection = CallLog.Calls.DATE + " >= ? AND " + CallLog.Calls.DATE + " <= ?";
        String[] selectionArgs = new String[]{
                String.valueOf(startMillis),
                String.valueOf(endMillis)
        };

        cursor = getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                CallLog.Calls.DATE + " DESC"
        );

        int count = 0;

        if (cursor != null) {
            int numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER);
            int nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME);
            int typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE);
            int dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE);
            int durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION);

            while (cursor.moveToNext()) {
                String number = cursor.getString(numberIndex);
                String name = cursor.getString(nameIndex);
                int type = cursor.getInt(typeIndex);
                long dateMillis = cursor.getLong(dateIndex);
                long durationSeconds = cursor.getLong(durationIndex);

                String callType = getCallTypeName(type);
                String dateReadable = readableDateFormat.format(new Date(dateMillis));

                JSONObject item = new JSONObject();
                item.put("date_millis", dateMillis);
                item.put("date_readable", dateReadable);
                item.put("name", name == null ? "" : name);
                item.put("number", number == null ? "" : number);
                item.put("type", callType);
                item.put("duration_seconds", durationSeconds);

                jsonArray.put(item);

                readable.append("------------------------------------------------------------\n");
                readable.append("Date: ").append(dateReadable).append("\n");
                readable.append("Name: ").append(name == null ? "" : name).append("\n");
                readable.append("Number: ").append(number == null ? "" : number).append("\n");
                readable.append("Type: ").append(callType).append("\n");
                readable.append("Duration: ").append(durationSeconds).append(" seconds\n\n");

                count++;
            }
        }

        if (cursor != null) {
            cursor.close();
        }

        JSONObject root = new JSONObject();
        root.put("backup_type", "call_log");
        root.put("backup_time", currentStamp);
        root.put("date_range_start_millis", startMillis);
        root.put("date_range_end_millis", endMillis);
        root.put("total_calls", count);
        root.put("calls", jsonArray);

        File baseDir = new File(getExternalFilesDir(null), "OfficeBackup/Calls");
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }

        File jsonFile = new File(baseDir, "call-log-" + currentStamp + ".json");
        File txtFile = new File(baseDir, "call-log-readable-" + currentStamp + ".txt");

        writeTextFile(jsonFile, root.toString(2));
        writeTextFile(txtFile, readable.toString());

        return new BackupResult(count, baseDir.getAbsolutePath());
    }

    private BackupResult backupSms(long startMillis, long endMillis) throws Exception {
        Cursor cursor = null;

        JSONArray jsonArray = new JSONArray();
        StringBuilder readable = new StringBuilder();

        readable.append("SMS READABLE SUMMARY\n");
        readable.append("============================================================\n\n");
        readable.append("Date range: ")
                .append(readableDateFormat.format(new Date(startMillis)))
                .append(" to ")
                .append(readableDateFormat.format(new Date(endMillis)))
                .append("\n\n");

        String[] projection = new String[]{
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
        };

        String selection = Telephony.Sms.DATE + " >= ? AND " + Telephony.Sms.DATE + " <= ?";
        String[] selectionArgs = new String[]{
                String.valueOf(startMillis),
                String.valueOf(endMillis)
        };

        cursor = getContentResolver().query(
                Telephony.Sms.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                Telephony.Sms.DATE + " DESC"
        );

        int count = 0;

        if (cursor != null) {
            int addressIndex = cursor.getColumnIndex(Telephony.Sms.ADDRESS);
            int bodyIndex = cursor.getColumnIndex(Telephony.Sms.BODY);
            int dateIndex = cursor.getColumnIndex(Telephony.Sms.DATE);
            int typeIndex = cursor.getColumnIndex(Telephony.Sms.TYPE);

            while (cursor.moveToNext()) {
                String address = cursor.getString(addressIndex);
                String body = cursor.getString(bodyIndex);
                long dateMillis = cursor.getLong(dateIndex);
                int type = cursor.getInt(typeIndex);

                String smsType = getSmsTypeName(type);
                String dateReadable = readableDateFormat.format(new Date(dateMillis));

                JSONObject item = new JSONObject();
                item.put("date_millis", dateMillis);
                item.put("date_readable", dateReadable);
                item.put("number", address == null ? "" : address);
                item.put("type", smsType);
                item.put("message", body == null ? "" : body);

                jsonArray.put(item);

                readable.append("------------------------------------------------------------\n");
                readable.append("Date: ").append(dateReadable).append("\n");
                readable.append("Number: ").append(address == null ? "" : address).append("\n");
                readable.append("Type: ").append(smsType).append("\n");
                readable.append("Message: ").append(body == null ? "" : body).append("\n\n");

                count++;
            }
        }

        if (cursor != null) {
            cursor.close();
        }

        JSONObject root = new JSONObject();
        root.put("backup_type", "sms");
        root.put("backup_time", currentStamp);
        root.put("date_range_start_millis", startMillis);
        root.put("date_range_end_millis", endMillis);
        root.put("total_sms", count);
        root.put("messages", jsonArray);

        File baseDir = new File(getExternalFilesDir(null), "OfficeBackup/Text");
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }

        File jsonFile = new File(baseDir, "sms-log-" + currentStamp + ".json");
        File txtFile = new File(baseDir, "sms-log-readable-" + currentStamp + ".txt");

        writeTextFile(jsonFile, root.toString(2));
        writeTextFile(txtFile, readable.toString());

        return new BackupResult(count, baseDir.getAbsolutePath());
    }

    private BackupResult backupGmail(
            String gmailEmail,
            String gmailAppPassword,
            long startMillis,
            long endMillis
    ) throws Exception {
        JSONArray jsonArray = new JSONArray();
        StringBuilder readable = new StringBuilder();

        readable.append("GMAIL READABLE SUMMARY\n");
        readable.append("============================================================\n\n");
        readable.append("Account: ").append(gmailEmail).append("\n");
        readable.append("Date range: ")
                .append(readableDateFormat.format(new Date(startMillis)))
                .append(" to ")
                .append(readableDateFormat.format(new Date(endMillis)))
                .append("\n");
        readable.append("Limit: Latest ").append(GMAIL_LIMIT).append(" matched emails from INBOX\n\n");

        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", "imap.gmail.com");
        props.put("mail.imaps.port", "993");
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.auth", "true");
        props.put("mail.imaps.connectiontimeout", "10000");
        props.put("mail.imaps.timeout", "10000");
        props.put("mail.imaps.writetimeout", "10000");
        props.put("mail.imaps.partialfetch", "true");
        props.put("mail.imaps.fetchsize", "32768");

        Session session = Session.getInstance(props);

        Store store = null;
        Folder inbox = null;

        int count = 0;

        try {
            store = session.getStore("imaps");
            store.connect("imap.gmail.com", gmailEmail, gmailAppPassword);

            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            SearchTerm startTerm = new ReceivedDateTerm(ComparisonTerm.GE, new Date(startMillis));
            SearchTerm endTerm = new ReceivedDateTerm(ComparisonTerm.LE, new Date(endMillis));
            SearchTerm dateRangeTerm = new AndTerm(startTerm, endTerm);

            Message[] messages = inbox.search(dateRangeTerm);

            Arrays.sort(messages, (m1, m2) ->
                    Long.compare(getMessageTimeSafe(m2), getMessageTimeSafe(m1))
            );

            int totalToSave = Math.min(GMAIL_LIMIT, messages.length);

            for (int i = 0; i < totalToSave; i++) {
                Message message = messages[i];

                String subject = message.getSubject() == null ? "" : message.getSubject();
                String from = addressesToString(message.getFrom());

                Date messageDate = message.getReceivedDate();
                if (messageDate == null) {
                    messageDate = message.getSentDate();
                }

                String dateReadable = messageDate == null ? "" : readableDateFormat.format(messageDate);

                String body = "";
                try {
                    body = getTextFromPart(message);
                } catch (Exception bodyError) {
                    body = "[Could not read email body: " + bodyError.getMessage() + "]";
                }

                JSONObject item = new JSONObject();
                item.put("date_readable", dateReadable);
                item.put("from", from);
                item.put("subject", subject);
                item.put("body", body == null ? "" : body);

                jsonArray.put(item);

                readable.append("------------------------------------------------------------\n");
                readable.append("Date: ").append(dateReadable).append("\n");
                readable.append("From: ").append(from).append("\n");
                readable.append("Subject: ").append(subject).append("\n");
                readable.append("Message:\n").append(body == null ? "" : body).append("\n\n");

                count++;

                final int progress = count;
                setStatusFromThread(
                        "Reading Gmail...\n" +
                                "Saved " + progress + " / " + totalToSave + " emails\n" +
                                fileDateFormat.format(new Date(startMillis)) +
                                " to " +
                                fileDateFormat.format(new Date(endMillis))
                );
            }

        } finally {
            if (inbox != null && inbox.isOpen()) {
                inbox.close(false);
            }

            if (store != null && store.isConnected()) {
                store.close();
            }
        }

        JSONObject root = new JSONObject();
        root.put("backup_type", "gmail");
        root.put("backup_time", currentStamp);
        root.put("gmail_account", gmailEmail);
        root.put("date_range_start_millis", startMillis);
        root.put("date_range_end_millis", endMillis);
        root.put("total_gmail_saved", count);
        root.put("emails", jsonArray);

        File baseDir = new File(getExternalFilesDir(null), "OfficeBackup/Gmail");
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }

        File jsonFile = new File(baseDir, "gmail-log-" + currentStamp + ".json");
        File txtFile = new File(baseDir, "gmail-log-readable-" + currentStamp + ".txt");

        writeTextFile(jsonFile, root.toString(2));
        writeTextFile(txtFile, readable.toString());

        return new BackupResult(count, baseDir.getAbsolutePath());
    }

    private long getMessageTimeSafe(Message message) {
        try {
            Date date = message.getReceivedDate();
            if (date == null) {
                date = message.getSentDate();
            }
            return date == null ? 0 : date.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private BackupResult writeGmailErrorFile(String gmailEmail, String errorText) throws Exception {
        File baseDir = new File(getExternalFilesDir(null), "OfficeBackup/Gmail");
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }

        JSONObject root = new JSONObject();
        root.put("backup_type", "gmail");
        root.put("backup_time", currentStamp);
        root.put("gmail_account", gmailEmail);
        root.put("total_gmail_saved", 0);
        root.put("error", errorText);

        StringBuilder readable = new StringBuilder();
        readable.append("GMAIL BACKUP ERROR\n");
        readable.append("============================================================\n\n");
        readable.append("Account: ").append(gmailEmail).append("\n\n");
        readable.append("Error:\n").append(errorText).append("\n\n");
        readable.append("Check Gmail App Password, internet connection, and Gmail IMAP access.");

        File jsonFile = new File(baseDir, "gmail-error-" + currentStamp + ".json");
        File txtFile = new File(baseDir, "gmail-error-readable-" + currentStamp + ".txt");

        writeTextFile(jsonFile, root.toString(2));
        writeTextFile(txtFile, readable.toString());

        return new BackupResult(0, baseDir.getAbsolutePath());
    }

    private String addressesToString(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        for (int i = 0; i < addresses.length; i++) {
            Address address = addresses[i];

            if (address instanceof InternetAddress) {
                InternetAddress internetAddress = (InternetAddress) address;
                String personal = internetAddress.getPersonal();
                String email = internetAddress.getAddress();

                if (personal != null && !personal.isEmpty()) {
                    result.append(personal).append(" <").append(email).append(">");
                } else {
                    result.append(email);
                }
            } else {
                result.append(address.toString());
            }

            if (i < addresses.length - 1) {
                result.append(", ");
            }
        }

        return result.toString();
    }

    private String getTextFromPart(Part part) throws Exception {
        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            return limitText(content == null ? "" : content.toString());
        }

        if (part.isMimeType("text/html")) {
            Object content = part.getContent();
            return limitText(content == null ? "" : htmlToPlainText(content.toString()));
        }

        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            StringBuilder result = new StringBuilder();

            for (int i = 0; i < multipart.getCount(); i++) {
                Part bodyPart = multipart.getBodyPart(i);

                String disposition = bodyPart.getDisposition();
                if (disposition != null && disposition.equalsIgnoreCase(Part.ATTACHMENT)) {
                    continue;
                }

                String text = getTextFromPart(bodyPart);
                if (text != null && !text.trim().isEmpty()) {
                    result.append(text.trim()).append("\n\n");
                }

                if (result.length() > 5000) {
                    break;
                }
            }

            return limitText(result.toString().trim());
        }

        return "";
    }

    private String limitText(String text) {
        if (text == null) {
            return "";
        }

        int maxChars = 5000;

        if (text.length() <= maxChars) {
            return text;
        }

        return text.substring(0, maxChars) + "\n\n[Message body shortened for faster backup]";
    }

    private String htmlToPlainText(String html) {
        if (html == null) {
            return "";
        }

        return html
                .replaceAll("(?s)<script.*?</script>", " ")
                .replaceAll("(?s)<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private File createZipFile(File officeBackupDir) throws Exception {
        File zipFile = new File(getExternalFilesDir(null), "office-backup-" + currentStamp + ".zip");

        if (zipFile.exists()) {
            zipFile.delete();
        }

        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));
        zipFolder(officeBackupDir, officeBackupDir.getName(), zos);
        zos.close();

        return zipFile;
    }

    private void zipFolder(File folder, String basePath, ZipOutputStream zos) throws Exception {
        File[] files = folder.listFiles();
        if (files == null) return;

        for (File file : files) {
            String entryName = basePath + "/" + file.getName();

            if (file.isDirectory()) {
                zipFolder(file, entryName, zos);
            } else {
                FileInputStream fis = new FileInputStream(file);
                ZipEntry zipEntry = new ZipEntry(entryName);
                zos.putNextEntry(zipEntry);

                byte[] buffer = new byte[4096];
                int length;

                while ((length = fis.read(buffer)) > 0) {
                    zos.write(buffer, 0, length);
                }

                zos.closeEntry();
                fis.close();
            }
        }
    }

    private String saveZipToDownloads(File zipFile) throws Exception {
        String fileName = zipFile.getName();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
            values.put(MediaStore.Downloads.RELATIVE_PATH, "Download");

            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);

            if (uri == null) {
                throw new Exception("Could not create ZIP in Downloads.");
            }

            OutputStream out = getContentResolver().openOutputStream(uri);
            FileInputStream in = new FileInputStream(zipFile);

            byte[] buffer = new byte[4096];
            int length;

            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }

            in.close();
            out.close();

            return "Downloads/" + fileName;
        } else {
            return zipFile.getAbsolutePath();
        }
    }

    private void deleteFolderContents(File folder) {
        if (folder == null || !folder.exists()) return;

        File[] files = folder.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                deleteFolderContents(file);
            }
            file.delete();
        }
    }

    private String getCallTypeName(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE:
                return "Incoming";
            case CallLog.Calls.OUTGOING_TYPE:
                return "Outgoing";
            case CallLog.Calls.MISSED_TYPE:
                return "Missed";
            case CallLog.Calls.REJECTED_TYPE:
                return "Rejected";
            case CallLog.Calls.BLOCKED_TYPE:
                return "Blocked";
            case CallLog.Calls.VOICEMAIL_TYPE:
                return "Voicemail";
            default:
                return "Unknown";
        }
    }

    private String getSmsTypeName(int type) {
        switch (type) {
            case Telephony.Sms.MESSAGE_TYPE_INBOX:
                return "Inbox";
            case Telephony.Sms.MESSAGE_TYPE_SENT:
                return "Sent";
            case Telephony.Sms.MESSAGE_TYPE_DRAFT:
                return "Draft";
            case Telephony.Sms.MESSAGE_TYPE_OUTBOX:
                return "Outbox";
            case Telephony.Sms.MESSAGE_TYPE_FAILED:
                return "Failed";
            case Telephony.Sms.MESSAGE_TYPE_QUEUED:
                return "Queued";
            default:
                return "Unknown";
        }
    }

    private void writeTextFile(File file, String content) throws Exception {
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(content.getBytes("UTF-8"));
        fos.close();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            updatePermissionStatus();
        }
    }

    private static class BackupResult {
        int count;
        String folderPath;

        BackupResult(int count, String folderPath) {
            this.count = count;
            this.folderPath = folderPath;
        }
    }

    private static class GmailAttempt {
        BackupResult result;
        String note;

        GmailAttempt(BackupResult result, String note) {
            this.result = result;
            this.note = note;
        }
    }
}