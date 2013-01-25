/*
 * Copyright (c) 2012-2013 The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux FOundation, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.phone;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.content.Intent;

public class ContactScreenActivity extends Activity {
    private static final String TAG = "ContactScreenActivity";
    String mName, mNewName;
    String mPhoneNumber, mNewPhoneNumber;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.contact_screen);

        try {
            final EditText editName = (EditText) this.findViewById(R.id.name);
            final EditText editPhoneNumber = (EditText) this.findViewById(R.id.phoneNumber);
            final Intent intent = getIntent();
            mName = intent.getStringExtra("NAME");
            mPhoneNumber = intent.getStringExtra("PHONE");

            editName.setText(mName, TextView.BufferType.EDITABLE);
            editPhoneNumber.setText(mPhoneNumber, TextView.BufferType.EDITABLE);

            View.OnClickListener handler = new View.OnClickListener(){
                public void onClick(View v) {
                    switch (v.getId()){
                        case R.id.save:
                            mNewName = editName.getText().toString();
                            mNewPhoneNumber = editPhoneNumber.getText().toString();
                            Log.d(TAG, "Name: " + mName + " Number: "
                                    + mPhoneNumber);
                            Log.d(TAG, " After edited Name: "
                                    + mNewName + " Number: " + mNewPhoneNumber);
                            Intent intent = new Intent();
                            intent.putExtra("NAME", mName);
                            intent.putExtra("PHONE", mPhoneNumber);
                            intent.putExtra("NEWNAME", mNewName);
                            intent.putExtra("NEWPHONE", mNewPhoneNumber);
                            setResult(RESULT_OK, intent);
                            finish();
                            break;
                        case R.id.cancel:
                            finish();
                            break;
                    }
                }
            };

            findViewById(R.id.save).setOnClickListener(handler);
            findViewById(R.id.cancel).setOnClickListener(handler);

        } catch(Exception e){
            Log.e("ContactScreenActivity ", e.toString());
        }
    }
}
