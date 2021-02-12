package com.hover.stax.navigation;

import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.amplitude.api.Amplitude;
import com.google.android.material.bottomappbar.BottomAppBar;
import com.hover.sdk.permissions.PermissionHelper;
import com.hover.stax.R;
import com.hover.stax.utils.Constants;
import com.hover.stax.permissions.PermissionUtils;
import com.hover.stax.settings.SettingsFragment;
import com.hover.stax.utils.UIHelper;

public abstract class AbstractNavigationActivity extends AppCompatActivity implements NavigationInterface {

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	protected void setUpNav() {
		BottomAppBar nav = findViewById(R.id.nav_view);
		NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment);
		AppBarConfiguration appBarConfiguration = new AppBarConfiguration.Builder(navController.getGraph()).build();
		NavigationUI.setupWithNavController(nav, navController, appBarConfiguration);

		nav.setOnMenuItemClickListener(item -> {
			checkPermissionsAndNavigate(getNavConst(item.getItemId()));
			return true;
		});

		navController.addOnDestinationChangedListener((controller, destination, arguments) -> setActiveNav(destination.getId(), nav));

		if (getIntent().getBooleanExtra(SettingsFragment.LANG_CHANGE, false)) navigate(this, Constants.NAV_SETTINGS);

	}

	private void setActiveNav(int destinationId, BottomAppBar nav) {
		UIHelper.changeDrawableColor(nav.findViewById(R.id.navigation_home), destinationId == R.id.navigation_home ? R.color.brightBlue : R.color.offWhite, this);
		UIHelper.changeDrawableColor(nav.findViewById(R.id.navigation_balance), destinationId == R.id.navigation_balance ? R.color.brightBlue : R.color.offWhite, this);
		UIHelper.changeDrawableColor(nav.findViewById(R.id.navigation_settings), destinationId == R.id.navigation_settings ? R.color.brightBlue : R.color.offWhite, this);
	}

	public void checkPermissionsAndNavigate(int toWhere) {
		PermissionHelper permissionHelper = new PermissionHelper(this);
		if (toWhere == Constants.NAV_SETTINGS || toWhere == Constants.NAV_HOME || permissionHelper.hasBasicPerms()) {
			navigate(this, toWhere, getIntent(), false);
		} else
			PermissionUtils.showInformativeBasicPermissionDialog(
				pos -> PermissionUtils.requestPerms(getNavConst(toWhere), AbstractNavigationActivity.this),
				neg -> Amplitude.getInstance().logEvent(getString(R.string.perms_basic_cancelled)),
				this);
	}


	private int getNavConst(int destId) {
		if (destId == R.id.navigation_balance) return Constants.NAV_BALANCE;
		else if (destId == R.id.navigation_settings) return Constants.NAV_SETTINGS;
		else if (destId == R.id.navigation_home) return Constants.NAV_HOME;
		else return destId;
	}

	public void onClickBackIcon(View v) { onBackPressed(); }
	public void onClickHomeIcon(View v) { navigateToMainActivity(this); }
	public void onClickBalanceIcon(View v) { navigateToMainActivityAndRedirectToAFragment(this, Constants.NAV_BALANCE); }
	public void onClickSettingsIcon(View v) { navigateToMainActivityAndRedirectToAFragment(this, Constants.NAV_SETTINGS); }

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		PermissionUtils.logPermissionsGranted(grantResults, this);
		checkPermissionsAndNavigate(requestCode);
	}
}
