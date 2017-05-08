package com.liuwei1995.zxing.zxing;

/**
 * Created by linxins on 17-5-8.
 */

import com.google.zxing.ResultPoint;
import com.google.zxing.ResultPointCallback;
import com.liuwei1995.zxing.zxing.view.ViewfinderView;

public class ViewfinderResultPointCallback implements ResultPointCallback {

    private final ViewfinderView viewfinderView;

    public ViewfinderResultPointCallback(ViewfinderView viewfinderView) {
        this.viewfinderView = viewfinderView;
    }

    @Override
    public void foundPossibleResultPoint(ResultPoint point) {
        viewfinderView.addPossibleResultPoint(point);
    }

}