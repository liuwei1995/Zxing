package com.liuwei1995.zxing.zxing.result;

import com.google.zxing.client.result.ParsedResult;
import com.liuwei1995.zxing.R;
import com.liuwei1995.zxing.zxing.CaptureActivity;

/**
 * Created by linxins on 17-5-8.
 */

import com.google.zxing.client.result.EmailAddressParsedResult;
import com.google.zxing.client.result.ParsedResult;

import android.app.Activity;

/**
 * Handles email addresses.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
public final class EmailAddressResultHandler extends ResultHandler {
    private static final int[] buttons = {
            R.string.button_email,
            R.string.button_add_contact
    };

    public EmailAddressResultHandler(Activity activity, ParsedResult result) {
        super(activity, result);
    }

    @Override
    public int getButtonCount() {
        return buttons.length;
    }

    @Override
    public int getButtonText(int index) {
        return buttons[index];
    }

    @Override
    public void handleButtonPress(int index) {
        EmailAddressParsedResult emailResult = (EmailAddressParsedResult) getResult();
        switch (index) {
            case 0:
                sendEmail(emailResult.getTos(),
                        emailResult.getCCs(),
                        emailResult.getBCCs(),
                        emailResult.getSubject(),
                        emailResult.getBody());
                break;
            case 1:
                addEmailOnlyContact(emailResult.getTos(), null);
                break;
        }
    }

    @Override
    public int getDisplayTitle() {
        return R.string.result_email_address;
    }
}