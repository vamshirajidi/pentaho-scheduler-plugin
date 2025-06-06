/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2029-07-20
 ******************************************************************************/


package org.pentaho.mantle.client.dialogs.scheduling;

import org.pentaho.gwt.widgets.client.dialogs.IDialogCallback;
import org.pentaho.gwt.widgets.client.dialogs.MessageDialogBox;
import org.pentaho.gwt.widgets.client.utils.NameUtils;
import org.pentaho.gwt.widgets.client.utils.string.StringUtils;
import org.pentaho.gwt.widgets.client.wizards.AbstractWizardDialog;
import org.pentaho.gwt.widgets.client.wizards.IWizardPanel;
import org.pentaho.mantle.client.messages.Messages;
import org.pentaho.mantle.client.environment.EnvironmentHelper;
import org.pentaho.mantle.client.workspace.JsJob;
import org.pentaho.mantle.client.workspace.JsJobParam;
import org.pentaho.mantle.login.client.MantleLoginDialog;

import com.google.gwt.core.client.JsArray;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.http.client.URL;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONParser;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.rpc.AsyncCallback;

import java.util.ArrayList;
import java.util.List;

public class ScheduleParamsDialog extends AbstractWizardDialog {

  IDialogCallback callback;
  IAfterResponse afterResponseCallback;

  ScheduleParamsWizardPanel scheduleParamsWizardPanel;
  ScheduleEmailDialog scheduleEmailDialog;
  ScheduleRecurrenceDialog parentDialog;

  String filePath;

  JSONObject jobSchedule;
  JsJob editJob;
  JSONArray scheduleParams;

  Boolean done = false;
  boolean isEmailConfValid = false;
  private boolean isNewJob = true;

  private boolean newSchedule = true;

  public ScheduleParamsDialog( ScheduleRecurrenceDialog parentDialog, boolean isEmailConfValid, JsJob editJob ) {
    super( ScheduleDialogType.SCHEDULER, Messages.getString( editJob == null ? "newSchedule" : "editSchedule" ),
      null, false, true );

    this.parentDialog = parentDialog;
    this.filePath = parentDialog.filePath;
    this.jobSchedule = parentDialog.getSchedule();
    this.isEmailConfValid = isEmailConfValid;
    this.editJob = editJob;

    initDialog();

    if ( isEmailConfValid ) {
      finishButton.setText( Messages.getString( "next" ) );
    }
  }

  public ScheduleParamsDialog( String filePath, JSONObject schedule, boolean isEmailConfValid ) {
    super( ScheduleDialogType.SCHEDULER, Messages.getString( "runInBackground" ), null, false, true );

    this.filePath = filePath;
    this.jobSchedule = schedule;
    this.isEmailConfValid = isEmailConfValid;

    initDialog();

    if ( isEmailConfValid ) {
      finishButton.setText( Messages.getString( "next" ) );
    }
  }

  @Override
  public boolean onKeyDownPreview( char key, int modifiers ) {
    if ( key == KeyCodes.KEY_ESCAPE ) {
      hide();
    }
    return true;
  }

  public void initDialog() {
    setResponsive( true );
    setSizingMode( DialogSizingMode.FILL_VIEWPORT );
    setWidthCategory( DialogWidthCategory.SMALL );

    scheduleParamsWizardPanel = new ScheduleParamsWizardPanel( filePath );
    IWizardPanel[] wizardPanels = { scheduleParamsWizardPanel };
    setWizardPanels( wizardPanels );
    setWidth( "800px" );
    String urlPath = URL.encodePathSegment( NameUtils.encodeRepositoryPath( filePath ) );

    String urlParams = "";
    if ( editJob != null ) {
      // add all edit params to URL
      JsArray<JsJobParam> jparams = editJob.getJobParams();
      for ( int i = 0; i < jparams.length(); i++ ) {
        urlParams += i == 0 ? "?" : "&";
        urlParams += jparams.get( i ).getName() + "=" + URL.encodeQueryString( jparams.get( i ).getValue().trim() );
      }
    }
    setParametersUrl( EnvironmentHelper.getFullyQualifiedURL() + "api/repos/" + urlPath + "/parameterUi" + urlParams );
    wizardDeckPanel.setHeight( "100%" );

    setSize( "650px", "450px" );
    addStyleName( "schedule-params-dialog" );
  }

  JSONArray getScheduleParams( boolean suppressAlerts ) {
    JsArray<JsSchedulingParameter> jsSchedulingParams = scheduleParamsWizardPanel.getParams( suppressAlerts );
    List<JSONObject> schedulingParams = new ArrayList<JSONObject>();
    for ( int i = 0; i < jsSchedulingParams.length(); i++ ) {
      schedulingParams.add( new JSONObject( jsSchedulingParams.get( i ) ) );
    }

    return ScheduleParamsHelper.getScheduleParams( jobSchedule, schedulingParams );
  }

  protected JSONArray getFinishScheduleParams() {
    JSONArray params = getScheduleParams( false );

    if ( editJob != null ) {
      params.set( params.size(), generateLineageId() );
    }

    return params;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.pentaho.gwt.widgets.client.wizards.AbstractWizardDialog#finish()
   */
  @Override
  protected boolean onFinish() {
    scheduleParams = getFinishScheduleParams();

    if ( editJob != null ) {
      scheduleParams.set( scheduleParams.size(), ScheduleParamsHelper.generateLineageId( editJob ) );
    }

    if ( isEmailConfValid ) {
      showScheduleEmailDialog( scheduleParams );
    } else {
      hide();

      if ( jobSchedule.containsKey( ScheduleParamsHelper.APPEND_DATE_FORMAT_KEY ) ) {
        jobSchedule.put( ScheduleParamsHelper.APPEND_DATE_FORMAT_KEY, null ); // will be stored in 'jobParameters'
      }
      if ( jobSchedule.containsKey( ScheduleParamsHelper.OVERWRITE_FILE_KEY ) ) {
        jobSchedule.put( ScheduleParamsHelper.OVERWRITE_FILE_KEY, null );
      }

      JSONObject scheduleRequest = parseStrictScheduleJob();
      scheduleRequest.put( ScheduleParamsHelper.JOB_PARAMETERS_KEY, scheduleParams );

      RequestBuilder scheduleFileRequestBuilder = ScheduleHelper.buildRequestForJob( editJob, scheduleRequest );

      try {
        scheduleFileRequestBuilder.sendRequest( scheduleRequest.toString(), new RequestCallback() {

          @Override
          public void onError( Request request, Throwable exception ) {
            MessageDialogBox dialogBox =
                new MessageDialogBox( Messages.getString( "error" ), exception.toString(), false, false, true );
            dialogBox.center();
            setDone( false );
          }

          @Override
          public void onResponseReceived( Request request, Response response ) {
            if ( response.getStatusCode() == 200 ) {
              setDone( true );
              ScheduleParamsDialog.this.hide();
              if ( callback != null ) {
                callback.okPressed();
              }

              if ( afterResponseCallback != null ) {
                afterResponseCallback.onResponse( jobSchedule.get( "runInBackground" ) );
              }
            } else {
              String message = response.getText();
              if ( StringUtils.isEmpty( message ) ) {
                message = Messages.getString( "serverErrorColon" ) + " " + response.getStatusCode();
              }

              MessageDialogBox dialogBox = new MessageDialogBox( Messages.getString( "error" ), message,
                false, false, true );

              dialogBox.center();
              setDone( false );
            }
          }

        } );
      } catch ( RequestException e ) {
        MessageDialogBox dialogBox = new MessageDialogBox( Messages.getString( "error" ), e.toString(),
            false, false, true );
        dialogBox.center();
        setDone( false );
      }
      setDone( true );
    }
    return false;
  }

  /* Visible for testing */
  JSONObject parseStrictScheduleJob() {
    return (JSONObject) JSONParser.parseStrict( jobSchedule.toString() );
  }

  /* Visible for testing */
  JSONObject generateLineageId() {
    return ScheduleParamsHelper.generateLineageId( editJob );
  }

  private void showScheduleEmailDialog( final JSONArray scheduleParams ) {

    try {
      final String url = EnvironmentHelper.getFullyQualifiedURL() + "api/mantle/isAuthenticated";
      RequestBuilder requestBuilder = new RequestBuilder( RequestBuilder.GET, url );
      requestBuilder.setHeader( "accept", "text/plain" );
      requestBuilder.setHeader( "If-Modified-Since", "01 Jan 1970 00:00:00 GMT" );
      requestBuilder.sendRequest( null, new RequestCallback() {

        @Override
        public void onError( Request request, Throwable caught ) {
          MantleLoginDialog.performLogin( new AsyncCallback<Boolean>() {

            @Override
            public void onFailure( Throwable caught ) {
            }

            @Override
            public void onSuccess( Boolean result ) {
              showScheduleEmailDialog( scheduleParams );
            }
          } );
        }

        @Override
        public void onResponseReceived( Request request, Response response ) {
          if ( scheduleEmailDialog == null ) {
            scheduleEmailDialog = ScheduleFactory.getInstance()
              .createScheduleEmailDialog( ScheduleParamsDialog.this, filePath, jobSchedule, scheduleParams, editJob );
            scheduleEmailDialog.setCallback( callback );
          } else {
            scheduleEmailDialog.setScheduleParams( scheduleParams );
            scheduleEmailDialog.setJobSchedule( jobSchedule );
          }
          scheduleEmailDialog.center();
          hide();
        }

      } );
    } catch ( RequestException e ) {
      Window.alert( e.getMessage() );
    }

  }

  public Boolean getDone() {
    return done;
  }

  public void setDone( Boolean done ) {
    this.done = done;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.pentaho.gwt.widgets.client.wizards.AbstractWizardDialog#onNext(org.pentaho.gwt.widgets.client.wizards.
   * IWizardPanel, org.pentaho.gwt.widgets.client.wizards.IWizardPanel)
   */
  @Override
  protected boolean onNext( IWizardPanel nextPanel, IWizardPanel previousPanel ) {
    return true;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.pentaho.gwt.widgets.client.wizards.AbstractWizardDialog#onPrevious(org.pentaho.gwt.widgets.client.wizards
   * .IWizardPanel, org.pentaho.gwt.widgets.client.wizards.IWizardPanel)
   */
  @Override
  protected void backClicked() {
    try {
      scheduleParams = getScheduleParams( true );
    } catch ( Exception e ) {
      // If error generate on trying to assign params while backing out,
      // obviously you want to ignore it.
    }
    parentDialog.center();
    hide();
  }

  @Override
  public void center() {
    if ( scheduleParams != null ) {
      // we have saved params from back/next
      String urlPath = URL.encodePathSegment( NameUtils.encodeRepositoryPath( filePath ) );
      String urlParams = "";
      for ( int i = 0; i < scheduleParams.size(); i++ ) {
        JSONObject o = scheduleParams.get( i ).isObject();
        // keys: name, stringValue, type
        JSONString name = o.get( "name" ).isString();
        JSONArray stringValueArr = o.get( "stringValue" ).isArray();

        for ( int j = 0; j < stringValueArr.size(); j++ ) {
          urlParams += ( i == 0 && j == 0 ) ? "?" : "&";
          urlParams +=
              name.stringValue().replace( "\"", "" )
                      + "=" +  URL.encodeQueryString( stringValueArr.get( j ).toString().replace( "\"", "" ) );
        }
      }
      setParametersUrl( EnvironmentHelper.getFullyQualifiedURL() + "api/repos/" + urlPath + "/parameterUi" + urlParams );
    }
    super.center();
  }

  public void setParametersUrl( String url ) {
    scheduleParamsWizardPanel.setParametersUrl( url );
  }

  @Override
  protected boolean onPrevious( IWizardPanel previousPanel, IWizardPanel currentPanel ) {
    return true;
  }

  @Override
  protected boolean showBack( int index ) {
    return parentDialog != null;
  }

  @Override
  protected boolean showFinish( int index ) {
    return true;
  }

  @Override
  protected boolean showNext( int index ) {
    return false;
  }

  @Override
  protected boolean enableBack( int index ) {
    return true;
  }

  public void setCallback( IDialogCallback callback ) {
    this.callback = callback;
  }

  public IDialogCallback getCallback() {
    return callback;
  }

  public void setAfterResponseCallback( IAfterResponse afterResponseCallback ) {
    this.afterResponseCallback = afterResponseCallback;
  }

  public ScheduleRecurrenceDialog getParentDialog() {
    return parentDialog;
  }

  public void setParentDialog( ScheduleRecurrenceDialog parentDialog ) {
    this.parentDialog = parentDialog;
  }

  public String getFilePath() {
    return filePath;
  }

  public void setFilePath( String filePath ) {
    this.filePath = filePath;
  }

  public JSONObject getJobSchedule() {
    return jobSchedule;
  }

  public void setJobSchedule( JSONObject jobSchedule ) {
    this.jobSchedule = jobSchedule;
  }

  public JsJob getEditJob() {
    return editJob;
  }

  public void setEditJob( JsJob editJob ) {
    this.editJob = editJob;
  }

  public boolean isEmailConfValid() {
    return isEmailConfValid;
  }

  public void setEmailConfValid( boolean isEmailConfValid ) {
    this.isEmailConfValid = isEmailConfValid;
  }

  public void setScheduleParams( JSONArray scheduleParams ) {
    this.scheduleParams = scheduleParams;
  }

  public interface IAfterResponse {
    void onResponse( JSONValue rib );
  }

  public void setNewSchedule( boolean newSchedule ) {
    this.newSchedule = newSchedule;
  }

  private boolean isNewSchedule() {
    return newSchedule;
  }

  public boolean isNewJob() {
    return isNewJob;
  }

  public void setNewJob( boolean isNewJob ) {
    this.isNewJob = isNewJob;
  }
}
