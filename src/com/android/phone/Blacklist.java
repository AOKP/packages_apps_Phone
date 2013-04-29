package com.android.phone;

import android.content.Context;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.CallerInfo;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

class Blacklist {
    private static final String LOG_TAG = "Blacklist";
    private static final boolean DBG = PhoneGlobals.DBG_LEVEL >= 2;

    private static final String BLFILE = "blacklist.dat";
    private static final int BLFILE_VER = 1;

    private Context mContext;
    private HashSet<PhoneNumber> mList = new HashSet<PhoneNumber>();

    public final static String PRIVATE_NUMBER ="0000";

    // Blacklist matching type
    public final static int MATCH_NONE = 0;
    public final static int MATCH_PRIVATE = 1;
    public final static int MATCH_UNKNOWN = 2;
    public final static int MATCH_LIST = 3;
    public final static int MATCH_REGEX = 4;

    public Blacklist(Context context) {
        mContext = context;
        load();
    }

    private void load() {
        ObjectInputStream ois = null;
        boolean valid = false;

        try {
            ois = new ObjectInputStream(mContext.openFileInput(BLFILE));
            Object o = ois.readObject();
            if (DBG) {
                Log.d(LOG_TAG, "Found object " + o);
            }
            if (o != null) {
                if (o instanceof Integer) {
                    // check the version
                    Integer version = (Integer) o;
                    if (version == BLFILE_VER) {
                        Object numbers = ois.readObject();
                        mList = (HashSet<PhoneNumber>) numbers;
                        valid = true;
                    }
                }
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Opening black list file failed", e);
        } catch (ClassNotFoundException e) {
            Log.e(LOG_TAG, "Found invalid contents in black list file", e);
        } catch (ClassCastException e) {
            Log.e(LOG_TAG, "Found invalid contents in black list file", e);
        } finally {
            if (ois != null) {
                try {
                    ois.close();
                } catch (IOException e) {
                    // Do nothing
                }
            }
        }

        if (!valid) {
            save();
        }
    }

    private void save() {
        ObjectOutputStream oos = null;
        try {
            oos = new ObjectOutputStream(mContext.openFileOutput(BLFILE, Context.MODE_PRIVATE));
            oos.writeObject(new Integer(BLFILE_VER));
            oos.writeObject(mList);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Could not save black list file", e);
            // ignore
        } finally {
            if (oos != null) {
                try {
                    oos.close();
                } catch (IOException e) {
                }
            }
        }
    }

    public boolean add(String s) {
        s = stripSeparators(s);
        if (TextUtils.isEmpty(s) || (matchesBlacklist(s) != MATCH_NONE)) {
            return false;
        }
        mList.add(new PhoneNumber(s));
        save();
        return true;
    }

    public void delete(String s) {
        for (PhoneNumber number : mList) {
            if (number.equals(s)) {
                mList.remove(number);
                save();
                return;
            }
        }
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
        return matchesBlacklist(s);
    }

    /**
     * See if the number is in the blacklist
     * @param s: Number to check
     * @return one of: MATCH_NONE, MATCH_PRIVATE, MATCH_UNKNOWN, MATCH_LIST or MATCH_REGEX
     */
    private int matchesBlacklist(String s) {
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

        // Standard list matching
        if (mList.contains(new PhoneNumber(s))) {
            return MATCH_LIST;
        }

        // Regex list matching
        if (!PhoneUtils.PhoneSettings.isBlacklistRegexEnabled(mContext)) {
            return MATCH_NONE;
        }
        for (PhoneNumber number : mList) {
            // Check for null (technically can't happen)
            // and make sure it doesn't begin with '*' to prevent FC's
            if (number.phone == null || number.phone.startsWith("*")) {
                continue;
            }
            // Escape all +'s. Other regex special chars
            // don't need to be checked for since the phone number
            // is already stripped of separator chars.
            String phone = number.phone.replaceAll("\\+", "\\\\+");
            if (s.matches(phone)) {
                return MATCH_REGEX;
            }
        }

        // Nothing matched
        return MATCH_NONE;
    }

    List<String> getItems() {
        List<String> items = new ArrayList<String>();
        for (PhoneNumber number : mList) {
            items.add(number.phone);
        }

        return items;
    }

    /**
     * Custom stripSeparators() method identical to
     * PhoneNumberUtils.stripSeparators(), to retain '.'s
     * for blacklist regex parsing.
     * There is no difference between the two, this is only
     * done to use the custom isNonSeparator() method below.
     */
    private String stripSeparators(String phoneNumber) {
        if (phoneNumber == null) {
            return null;
        }
        int len = phoneNumber.length();
        StringBuilder ret = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = phoneNumber.charAt(i);
            if (isNonSeparator(c)) {
                ret.append(c);
            }
        }

        return ret.toString();
    }

    /**
     * Custom isNonSeparator() method identical to
     * PhoneNumberUtils.isNonSeparator(), to retain '.'s
     * for blacklist regex parsing.
     * The only difference between the two is that this
     * custom one allows '.'s.
     */
    private boolean isNonSeparator(char c) {
        return (c >= '0' && c <= '9') || c == '*' || c == '#' || c == '+'
                    || c == PhoneNumberUtils.WILD || c == PhoneNumberUtils.WAIT
                    || c == PhoneNumberUtils.PAUSE || c == '.';
    }

    static class PhoneNumber implements Comparable<PhoneNumber>, Externalizable, Serializable {
        static final long serialVersionUID = 32847013274L;

        String phone;

        public PhoneNumber() {
            phone = null;
        }

        public PhoneNumber(String s) {
            phone = s;
        }

        public int compareTo(PhoneNumber bp) {
            if (bp == null || bp.phone == null) {
                return 1;
            }
            if (phone == null) {
                return -1;
            }
            return PhoneNumberUtils.compare(phone, bp.phone) ? 0 : phone.compareTo(bp.phone);
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof PhoneNumber) {
                return compareTo((PhoneNumber) o) == 0;
            }
            if (o instanceof CharSequence) {
                return TextUtils.equals((CharSequence) o, phone);
            }
            return false;
        }

        @Override
        public int hashCode() {
            if (phone == null) {
                return 0;
            }
            return phone.hashCode();
        }

        public void writeExternal(ObjectOutput out) throws IOException {
            out.writeObject(phone);
        }

        public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            phone = (String) in.readObject();
        }

        public String toString() {
            return "PhoneNumber: " + phone;
        }
    }
}
