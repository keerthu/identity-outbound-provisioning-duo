/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.provisioning.connector;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.wso2.carbon.identity.application.common.model.Property;
import org.wso2.carbon.identity.provisioning.*;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

public class DuoProvisioningConnector extends AbstractOutboundProvisioningConnector {

    private static final long serialVersionUID = 8465869197181038416L;

    private static final Log log = LogFactory.getLog(DuoProvisioningConnector.class);
    private DuoProvisioningConnectorConfig configHolder;

    @Override
    public void init(Property[] provisioningProperties) throws IdentityProvisioningException {
        Properties configs = new Properties();
        if (provisioningProperties != null && provisioningProperties.length > 0) {
            for (Property property : provisioningProperties) {
                configs.put(property.getName(), property.getValue());
                if (IdentityProvisioningConstants.JIT_PROVISIONING_ENABLED.equals(property
                        .getName())) {
                    if ("1".equals(property.getValue())) {
                        jitProvisioningEnabled = true;
                    }
                }
            }
        }
        configHolder = new DuoProvisioningConnectorConfig(configs);
    }

    @Override
    public ProvisionedIdentifier provision(ProvisioningEntity provisioningEntity)
            throws IdentityProvisioningException {
        try {
            String provisionedId = null;
            if (log.isDebugEnabled()) {
                log.debug("Provisioning Identifier : " + provisioningEntity.getIdentifier().getIdentifier());
            }
            if (provisioningEntity.isJitProvisioning() && !isJitProvisioningEnabled()) {
                if (log.isDebugEnabled()) {
                    log.debug("JIT provisioning disabled for Duo connector");
                }
                return null;
            }
            if (provisioningEntity.getEntityType() == ProvisioningEntityType.USER) {
                if (provisioningEntity.getOperation() == ProvisioningOperation.DELETE) {
                    deleteUser(provisioningEntity);
                } else if (provisioningEntity.getOperation() == ProvisioningOperation.POST) {
                    provisionedId = createUser(provisioningEntity);
                } else if (provisioningEntity.getOperation() == ProvisioningOperation.PUT) {
                    updateUser(provisioningEntity);
                } else {
                    log.warn("Unsupported provisioning operation.");
                }
            } else {
                log.warn("Unsupported provisioning entity type.");
            }
            //creates identifier for the provisioned user
            ProvisionedIdentifier identifier = new ProvisionedIdentifier();
            if (StringUtils.isNotEmpty(provisionedId) && !"0".equals(provisionedId)) {
                identifier.setIdentifier(provisionedId);
            }
            return identifier;
        } catch (IdentityProvisioningException e) {
            log.error(e.getMessage(), e);
            return null;
        }
    }

    /**
     * Creates user in Duo
     *
     * @throws IdentityProvisioningException
     */
    private String createUser(ProvisioningEntity provisioningEntity)
            throws IdentityProvisioningException {
        Object result;
        JSONObject jo;
        String provisionedId = null;
        Map<String, String> requiredAttributes = getSingleValuedClaims(provisioningEntity
                .getAttributes());
        requiredAttributes.put(DuoConnectorConstants.USERNAME, provisioningEntity.getEntityName());
        try {
            result = httpCall(DuoConnectorConstants.HttpMethods.POST, DuoConnectorConstants.API_USER, requiredAttributes);
            if (result != null) {
                jo = new JSONObject(result.toString());
                provisionedId = jo.getString(DuoConnectorConstants.USER_ID);
            }
        } catch (UnsupportedEncodingException | JSONException e) {
            throw new IdentityProvisioningException(
                    DuoConnectorConstants.DuoErrors.ERROR_CREATE_USER + provisioningEntity.getEntityName(), e);
        } catch (Exception e) {
            throw new IdentityProvisioningException(
                    DuoConnectorConstants.DuoErrors.ERROR_CREATE_USER + provisioningEntity.getEntityName(), e);
        }
        if (log.isDebugEnabled()) {
            log.debug("username for the created user : " + provisioningEntity.getEntityName());
            log.debug("Returning created user's ID : " + provisionedId);
        }
        return provisionedId;
    }

    /**
     * Deletes user in Duo
     *
     * @throws IdentityProvisioningException
     */
    private void deleteUser(ProvisioningEntity provisioningEntity)
            throws IdentityProvisioningException {
        String userID;
        try {
            userID = provisioningEntity.getIdentifier().getIdentifier().trim();
            if (StringUtils.isNotEmpty(userID)) {
                httpCall(DuoConnectorConstants.HttpMethods.DELETE, DuoConnectorConstants.API_USER + "/" + userID, null);
            }
        } catch (Exception e) {
            throw new IdentityProvisioningException(
                    DuoConnectorConstants.DuoErrors.ERROR_DELETE_USER + provisioningEntity.getEntityName(), e);
        }
        if (log.isDebugEnabled()) {
            log.debug("Deleted user in Duo : " + provisioningEntity.getEntityName());
        }
    }

    /**
     * Primary update method to update user in Duo
     * This method calls secondary methods to update user
     *
     * @throws IdentityProvisioningException
     */
    private void updateUser(ProvisioningEntity provisioningEntity) throws IdentityProvisioningException {
        String userID;
        String phoneNumber;
        boolean isUpdateNeeded = false;
        Map<String, String> requiredAttributes = getSingleValuedClaims(provisioningEntity.getAttributes());
        Iterator<Map.Entry<String, String>> iterator = requiredAttributes.entrySet().iterator();
        //checking if there are fields to be updated
        while (iterator.hasNext()) {
            Map.Entry<String, String> mapEntry = iterator.next();
            if (mapEntry.getValue() != null) {
                isUpdateNeeded = true;
                break;
            }
        }
        if (!isUpdateNeeded) {
            return;
        }
        userID = provisioningEntity.getIdentifier().getIdentifier().trim();
        phoneNumber = requiredAttributes.get(DuoConnectorConstants.PHONE_NUMBER);
        //updating email and real name fields
        modifyDuoUser(userID, requiredAttributes);
        if (phoneNumber != null && !phoneNumber.isEmpty()) {
            //updating phone number
            addPhoneToUser(userID, phoneNumber);
        } else {
            //Removing existing phone number from Duo Account
            String phoneID = getPhoneByUserId(userID);
            if (StringUtils.isNotEmpty(phoneID)) {
                removePhoneFromUser(phoneID, userID);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Updated Duo user : " + provisioningEntity.getEntityName());
        }
    }

    /**
     * Creates a device in Duo for the given phone number
     *
     * @param phone The phone number.
     * @throws IdentityProvisioningException
     */
    private String createPhone(Map<String, String> phone) throws IdentityProvisioningException {
        String phoneID = null;
        Object result;
        JSONObject jo;
        try {
            result = httpCall(DuoConnectorConstants.HttpMethods.POST, DuoConnectorConstants.API_PHONE, phone);
            if (result != null) {
                jo = new JSONObject(result.toString());
                phoneID = jo.getString(DuoConnectorConstants.PHONE_ID);
                if (log.isDebugEnabled()) {
                    log.debug("Created phone in Duo : " + phone.get(DuoConnectorConstants.PHONE_NUMBER));
                }
            }
        } catch (JSONException e) {
            throw new IdentityProvisioningException(
                    DuoConnectorConstants.DuoErrors.ERROR_CREATE_PHONE + DuoConnectorConstants.PHONE_NUMBER, e);
        } catch (Exception e) {
            throw new IdentityProvisioningException(
                    DuoConnectorConstants.DuoErrors.ERROR_CREATE_PHONE + DuoConnectorConstants.PHONE_NUMBER, e);
        }
        return phoneID;
    }

    /**
     * Updates email and real name fields in Duo user account
     *
     * @param userId     The user's ID
     * @param attributes Required parameters
     * @throws IdentityProvisioningException
     */
    private void modifyDuoUser(String userId, Map<String, String> attributes) throws IdentityProvisioningException {
        if (attributes.get(DuoConnectorConstants.PHONE_NUMBER) != null) {
            attributes.remove(DuoConnectorConstants.PHONE_NUMBER);
        }
        try {
            httpCall(DuoConnectorConstants.HttpMethods.POST, DuoConnectorConstants.API_USER + "/" +
                    userId, attributes);
        } catch (UnsupportedEncodingException e) {
            throw new IdentityProvisioningException(
                    DuoConnectorConstants.DuoErrors.ERROR_UPDATE_USER, e);
        } catch (Exception e) {
            throw new IdentityProvisioningException(
                    DuoConnectorConstants.DuoErrors.ERROR_UPDATE_USER, e);
        }
        return;
    }

    /**
     * Registers a phone number against a user account in Duo. If the user already have a different phone number,
     * it is removed before adding the new one.
     * If Duo already knows the given phone number, It's Id is saved against the user. If Duo doesn't have the
     * given phone number, a new device is created with a new Id and it is saved against the user.
     *
     * @param userId The user's ID
     * @param phone  The phone's ID
     * @throws IdentityProvisioningException
     */
    private void addPhoneToUser(String userId, String phone) throws IdentityProvisioningException {
        String phoneID = null;
        Map<String, String> param = new HashMap<String, String>();
        param.put(DuoConnectorConstants.PHONE_NUMBER, phone);
        try {
            phoneID = getPhoneByNumber(param);
        } catch (IdentityProvisioningException e) {
            //Ignoring the case
            if (log.isDebugEnabled()) {
                log.debug(DuoConnectorConstants.DuoErrors.ERROR_RETRIEVE_PHONE + phone);
            }
        }
        String currentPhone = getPhoneByUserId(userId);
        if (phoneID == null) {
            phoneID = createPhone(param);
        } else if (phoneID.equals(currentPhone)) {
            return;
        } else if (StringUtils.isNotEmpty(currentPhone)) {
            removePhoneFromUser(currentPhone, userId);
        }
        param.remove(DuoConnectorConstants.PHONE_NUMBER);
        param.put(DuoConnectorConstants.PHONE_ID, phoneID);
        try {
            httpCall(DuoConnectorConstants.HttpMethods.POST, DuoConnectorConstants.API_USER + "/" +
                    userId + "/phones", param);
        } catch (UnsupportedEncodingException e) {
            throw new IdentityProvisioningException(DuoConnectorConstants.DuoErrors.ERROR_ADDING_PHONE, e);
        } catch (Exception e) {
            throw new IdentityProvisioningException(DuoConnectorConstants.DuoErrors.ERROR_ADDING_PHONE, e);
        }
    }

    /**
     * Returns th Id of the phone in Duo account, registered with the given number.
     * Returns null if the number is not registered in Duo
     *
     * @param phone The phone's ID
     * @throws IdentityProvisioningException
     */
    private String getPhoneByNumber(Map<String, String> phone) throws IdentityProvisioningException {
        Object result;
        String phoneID = null;
        JSONArray jo;
        try {
            result = httpCall(DuoConnectorConstants.HttpMethods.GET, DuoConnectorConstants.API_PHONE, phone);
            if (result != null) {
                jo = new JSONArray(result.toString());
                if (jo.length() > 0) {
                    phoneID = jo.getJSONObject(0).getString(DuoConnectorConstants.PHONE_ID);
                }
            }
        } catch (UnsupportedEncodingException | JSONException e) {
            throw new IdentityProvisioningException(DuoConnectorConstants.DuoErrors.ERROR_RETRIEVE_PHONE +
                    phone.get(DuoConnectorConstants.PHONE_NUMBER), e);
        } catch (Exception e) {
            throw new IdentityProvisioningException(DuoConnectorConstants.DuoErrors.ERROR_RETRIEVE_PHONE +
                    phone.get(DuoConnectorConstants.PHONE_NUMBER), e);
        }
        return phoneID;
    }

    /**
     * Returns the Phone Id for a given user if there are registered devices. Otherwise returns null
     *
     * @param userId The user's ID
     * @throws IdentityProvisioningException
     */
    private String getPhoneByUserId(String userId) throws IdentityProvisioningException {
        Object result;
        String phoneID = null;
        JSONArray jo;
        try {
            result = httpCall(DuoConnectorConstants.HttpMethods.GET,
                    DuoConnectorConstants.API_USER + "/" + userId + "/phones", null);
            if (result != null) {
                jo = new JSONArray(result.toString());
                if (jo.length() > 0) {
                    phoneID = jo.getJSONObject(0).getString(DuoConnectorConstants.PHONE_ID);
                }
            }
        } catch (UnsupportedEncodingException | JSONException | IdentityProvisioningException e) {
            throw new IdentityProvisioningException(
                    DuoConnectorConstants.DuoErrors.ERROR_RETRIEVE_PHONE_FOR_USER, e);
        } catch (Exception e) {
            throw new IdentityProvisioningException(
                    DuoConnectorConstants.DuoErrors.ERROR_RETRIEVE_PHONE_FOR_USER, e);
        }
        return phoneID;
    }

    /**
     * Removes a given Phone Id from Duo user account
     *
     * @param phoneId The phone’s ID
     * @param userId  The user's ID
     * @throws IdentityProvisioningException
     */
    private void removePhoneFromUser(String phoneId, String userId) throws IdentityProvisioningException {
        try {
            httpCall(DuoConnectorConstants.HttpMethods.DELETE,
                    DuoConnectorConstants.API_USER + "/" + userId + "/phones/" + phoneId, null);
        } catch (Exception e) {
            throw new IdentityProvisioningException(DuoConnectorConstants.DuoErrors.ERROR_DELETE_PHONE, e);
        }
    }

    /**
     * Executes Duo API call and return the resulting Object
     *
     * @param method Api Method type
     * @param URI    Api endpoint
     * @param param  Required parameter
     * @throws Exception
     */
    private Object httpCall(String method, String URI, Map param) throws Exception {
        Object result;
        DuoHttp request = new DuoHttp(method, configHolder.getValue(DuoConnectorConstants.HOST), URI);
        if (param != null) {
            for (Map.Entry<String, String> mapEntry : (Iterable<Map.Entry<String, String>>) param.entrySet()) {
                if (mapEntry.getValue() != null) {
                    request.addParam(mapEntry.getKey(), mapEntry.getValue());
                }
            }
        }
        request.signRequest(configHolder.getValue(DuoConnectorConstants.IKEY),
                configHolder.getValue(DuoConnectorConstants.SKEY));
        result = request.executeRequest();
        if (result != null && log.isDebugEnabled()) {
            log.debug("API response from Duo : " + result.toString());
        }
        return result;
    }
}
