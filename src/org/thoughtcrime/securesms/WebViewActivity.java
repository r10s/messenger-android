package org.thoughtcrime.securesms;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.widget.SearchView;

import org.thoughtcrime.securesms.util.DynamicLanguage;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.Util;

public class WebViewActivity extends PassphraseRequiredActionBarActivity
                               implements SearchView.OnQueryTextListener,
                                          WebView.FindListener
{
  private static final String TAG = WebViewActivity.class.getSimpleName();

  protected WebView webView;
  protected String imageUrl;
  private final DynamicTheme dynamicTheme = new DynamicTheme();
  protected final DynamicLanguage dynamicLanguage = new DynamicLanguage();

  @Override
  protected void onPreCreate() {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
  }

  @Override
  protected void onCreate(Bundle state, boolean ready) {
    setContentView(R.layout.web_view_activity);
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);

    webView = findViewById(R.id.webview);
    registerForContextMenu(webView);
    webView.setWebViewClient(new WebViewClient() {
      // `shouldOverrideUrlLoading()` is called when the user clicks a URL,
      // returning `true` means, the URL is passed to `loadUrl()`, `false` aborts loading.
      // the method is not called for POST request nor for on-page-links.
      //
      // nb: from API 24, `shouldOverrideUrlLoading(String)` is deprecated and
      // `shouldOverrideUrlLoading(WebResourceRequest)` shall be used.
      // the new one has the same functionality, and the old one still exist,
      // so, to support all systems, for now, using the old one seems to be the simplest way.
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, String url) {
        if (url != null) {
          String schema = url.split(":")[0].toLowerCase();
          switch (schema) {
            case "http":
            case "https":
            case "mailto":
            case "openpgp4fpr":
              openOnlineUrl(url);
              // URL opened externally, returning `true` causes the WebView to abort loading
              return true;
          }
        }
        // by returning `true`, we also abort loading other URLs in our WebView;
        // eg. that might be weird or internal protocols.
        // if we come over really useful things, we should allow that explicitly.
        return true;
      }
    });
    webView.setFindListener(this);

    // disable "safe browsing" as this has privacy issues,
    // eg. at least false positives are sent to the "Safe Browsing Lookup API".
    // as all URLs opened in the WebView are local anyway,
    // "safe browsing" will never be able to report issues, so it can be disabled.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      webView.getSettings().setSafeBrowsingEnabled(false);
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
  }

  @Override
  public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
    if (v instanceof WebView) {
      WebView.HitTestResult result = ((WebView) v).getHitTestResult();
      if (result != null) {
        int type = result.getType();
        if (type == WebView.HitTestResult.IMAGE_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
          imageUrl = result.getExtra();
          super.onCreateContextMenu(menu, v, menuInfo);
          this.getMenuInflater().inflate(R.menu.web_view_context, menu);
          menu.setHeaderIcon(android.R.drawable.ic_menu_gallery);
          if (imageUrl.startsWith("data:")) {
            menu.setHeaderTitle(getString(R.string.image));
            menu.findItem(R.id.action_copy_link).setVisible(false);
          } else {
            menu.setHeaderTitle(imageUrl);
            menu.findItem(R.id.action_copy_link).setVisible(true);
          }
        }
      }
    }
  }

  @Override
  public boolean onContextItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case R.id.action_export_image:
        // TODO: extract image from "data:" link or download URL
        return true;
      case R.id.action_copy_link:
        Util.writeTextToClipboard(this, imageUrl);
        Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show();
        return true;
      default:
        return super.onContextItemSelected(item);
    }
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuInflater inflater = this.getMenuInflater();
    menu.clear();

    inflater.inflate(R.menu.web_view, menu);

    try {
      MenuItem searchItem = menu.findItem(R.id.menu_search_web_view);
      searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
        @Override
        public boolean onMenuItemActionExpand(final MenuItem item) {
          searchMenu = menu;
          WebViewActivity.this.lastQuery = "";
          WebViewActivity.this.makeSearchMenuVisible(menu, searchItem, true);
          return true;
        }

        @Override
        public boolean onMenuItemActionCollapse(final MenuItem item) {
          WebViewActivity.this.makeSearchMenuVisible(menu, searchItem, false);
          return true;
        }
      });
      SearchView searchView = (SearchView) searchItem.getActionView();
      searchView.setOnQueryTextListener(this);
      searchView.setQueryHint(getString(R.string.search));
      searchView.setIconifiedByDefault(true);

      // hide the [X] beside the search field - this is too much noise, search can be aborted eg. by "back"
      ImageView closeBtn = searchView.findViewById(R.id.search_close_btn);
      if (closeBtn!=null) {
        closeBtn.setEnabled(false);
        closeBtn.setImageDrawable(null);
      }
    } catch (Exception e) {
      Log.e(TAG, "cannot set up web-view-search: ", e);
    }

    super.onPrepareOptionsMenu(menu);
    return true;
  }


  // search

  private Menu   searchMenu = null;
  private String lastQuery = "";
  private Toast  lastToast = null;

  private void updateResultCounter(int curr, int total) {
    if (searchMenu!=null) {
      MenuItem item = searchMenu.findItem(R.id.menu_search_counter);
      if (curr!=-1) {
        item.setTitle(String.format("%d/%d", total==0? 0 : curr+1, total));
        item.setVisible(true);
      } else {
        item.setVisible(false);
      }
    }
  }

  @Override
  public boolean onQueryTextSubmit(String query) {
    return true; // action handled by listener
  }

  @Override
  public boolean onQueryTextChange(String query) {
    String normQuery = query.trim();
    lastQuery = normQuery;
    if (lastToast!=null) {
      lastToast.cancel();
      lastToast = null;
    }
    webView.findAllAsync(normQuery);
    return true; // action handled by listener
  }

  @Override
  public void onFindResultReceived (int activeMatchOrdinal, int numberOfMatches, boolean isDoneCounting)
  {
    if (isDoneCounting) {
      if (numberOfMatches>0) {
        updateResultCounter(activeMatchOrdinal, numberOfMatches);
      } else {
        if (lastQuery.isEmpty()) {
          updateResultCounter(-1, 0); // hide
        } else {
          String msg = getString(R.string.search_no_result_for_x, lastQuery);
          if (lastToast != null) {
            lastToast.cancel();
          }
          lastToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
          lastToast.show();
          updateResultCounter(0, 0); // show as "0/0"
        }
      }
    }
  }


  // other actions

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);
    switch (item.getItemId()) {
      case android.R.id.home:
        finish();
        return true;
      case R.id.menu_search_up:
        if (lastQuery.isEmpty()) {
          webView.scrollTo(0, 0);
        } else {
          webView.findNext(false);
        }
        return true;
      case R.id.menu_search_down:
        if (lastQuery.isEmpty()) {
          webView.scrollTo(0, 1000000000);
        } else {
          webView.findNext(true);
        }
        return true;
    }
    return false;
  }

  @Override
  public void onBackPressed() {
    if (webView.canGoBack()) {
      webView.goBack();
    } else {
      super.onBackPressed();
    }
  }

  protected void openOnlineUrl(String url) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (ActivityNotFoundException e) {
      Toast.makeText(this, R.string.no_browser_installed, Toast.LENGTH_LONG).show();
    }
  }

  @Override
  public void onActivityResult(int reqCode, int resultCode, final Intent data) {
    super.onActivityResult(reqCode, resultCode, data);
  }
}
