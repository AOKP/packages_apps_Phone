package com.android.phone;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.CallerInfo;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

class Blacklist {
    public final static String PRIVATE_NUMBER = "0000";

    // Blacklist matching type
    public final static int MATCH_NONE = 0;
    public final static int MATCH_PRIVATE = 1;
    public final static int MATCH_UNKNOWN = 2;
    public final static int MATCH_LIST = 3;
    public final static int MATCH_REGEX = 4;

    private Context mContext;

    public Blacklist(Context context) {
        mContext = context;
        migrateOldDataIfPresent();
    }

    // legacy migration code start

    private static class PhoneNumber implements Externalizable {
        static final long serialVersionUID = 32847013274L;
        String phone;

        public PhoneNumber() {
        }
        public void writeExternal(ObjectOutput out) throws IOException {
        }
        public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            phone = (String) in.readObject();
        }
        @Override
        public int hashCode() {
            return phone != null ? phone.hashCode() : 0;
        }
    }

    private static final String BLFILE = "blacklist.dat";
    private static final int BLFILE_VER = 1;

    private void migrateOldDataIfPresent() {
        ObjectInputStream ois = null;
        HashSet<PhoneNumber> data = null;

        try {
            ois = new ObjectInputStream(mContext.openFileInput(BLFILE));
            Object o = ois.readObject();
            if (o != null && o instanceof Integer) {
                // check the version
                Integer version = (Integer) o;
                if (version == BLFILE_VER) {
                    Object numbers = ois.readObject();
                    if (numbers instanceof HashSet) {
                        data = (HashSet<PhoneNumber>) numbers;
                    }
                }
            }
        } catch (IOException e) {
            // Do nothing
        } catch (ClassNotFoundException e) {
            // Do nothing
        } finally {
            if (ois != null) {
                try {
                    ois.close();
                } catch (IOException e) {
                    // Do nothing
                }
                mContext.deleteFile(BLFILE);
            }
        }
        if (data != null) {
            ContentResolver cr = mContext.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(Telephony.Blacklist.PHONE_MODE, 1);

            for (PhoneNumber number : data) {
                Uri uri = Uri.withAppendedPath(
                        Telephony.Blacklist.CONTENT_FILTER_BYNUMBER_URI, number.phone);
                cv.put(Telephony.Blacklist.NUMBER, number.phone);
                cr.update(uri, cv, null, null);
            }
        }
    }

    // legacy migration code end

    public boolean add(String s) {
        ContentValues cv = new ContentValues();
        cv.put(Telephony.Blacklist.NUMBER, s);
        cv.put(Telephony.Blacklist.PHONE_MODE, 1);

        Uri uri = mContext.getContentResolver().insert(Telephony.Blacklist.CONTENT_URI, cv);
        return uri != null;
    }

    public void delete(String s) {
        Uri uri = Uri.withAppendedPath(Telephony.Blacklist.CONTENT_FILTER_BYNUMBER_URI, s);
        mContext.getContentResolver().delete(uri, null, null);
    }

    /**
     * Check if the number is in the blacklist
     * @param s: Number to check
     * @return one of: MATCH_NONE, MATCH_PRIVATE, MATCH_UNKNOWN, MATCH_LIST or MATCH_REGEX
     */
    public int isListed(String s) {
        if (!PhoneUtils.PhoneSettings.isBlacklistEnabled(mContext)) {
            return MATCH_NONE;
        }

        // Private and unknown number matching
        if (s.equals(PRIVATE_NUMBER)) {
            if (PhoneUtils.PhoneSettings.isBlacklistPrivateNumberEnabled(mContext)) {
                return MATCH_PRIVATE;
            }
            return MATCH_NONE;
        }

        if (PhoneUtils.PhoneSettings.isBlacklistUnknownNumberEnabled(mContext)) {
            CallerInfo ci = CallerInfo.getCallerInfo(mContext, s);
            if (!ci.contactExists) {
                return MATCH_UNKNOWN;
            }
        }

        Uri.Builder builder = Telephony.Blacklist.CONTENT_FILTER_BYNUMBER_URI.buildUpon();
        builder.appendPath(s);
        if (PhoneUtils.PhoneSettings.isBlacklistRegexEnabled(mContext)) {
            builder.appendQueryParameter(Telephony.Blacklist.REGEX_KEY, "1");
        }

        int result = MATCH_NONE;
        Cursor c = mContext.getContentResolver().query(builder.build(), null,
                Telephony.Blacklist.PHONE_MODE + " != 0", null, null);
        if (c != null) {
            if (c.getCount() > 1) {
                // as the numbers are unique, this is guaranteed to be a regex match
                result = MATCH_REGEX;
            } else if (c.moveToFirst()) {
                boolean isRegex = c.getInt(c.getColumnIndex(Telephony.Blacklist.IS_REGEX)) != 0;
                result = isRegex ? MATCH_REGEX : MATCH_LIST;
            }
            c.close();
        }

        return result;
    }

    public List<String> getItems() {
        List<String> items = new ArrayList<String>();
        Cursor c = mContext.getContentResolver().query(Telephony.Blacklist.CONTENT_PHONE_URI,
                null, null, null, null);
        if (c != null) {
            int columnIndex = c.getColumnIndex(Telephony.Blacklist.NUMBER);
            c.moveToPosition(-1);
            while (c.moveToNext()) {
                items.add(c.getString(columnIndex));
            }
            c.close();
        }

        return items;
    }
}
