import React, { useEffect, useState } from "react";
import propTypes from "prop-types";
import MenuItem from "./MenuItem";
import Requests from "../../utils/Requests";
import * as RequestMethods from "../../constants/appConstants/RequestsMethods";
import ContextMenuActions from "../../actions/ContextMenuActions";
import subscribeSVG from "../../assets/images/context/subscribe.svg";
import unsubscribeSVG from "../../assets/images/context/unsubscribe.svg";
import SnackbarUtils from "../Notifications/Snackbars/SnackController";

type Subscription = {
  state: string;
};

type Props = {
  id: string;
  name: string;
};
export default function SubscriptionControl({ id, name }: Props) {
  const [subscription, setSubscription] = useState<Subscription | null>(null);

  useEffect(() => {
    setSubscription(null);
    Requests.sendGenericRequest(
      `/files/${id}/subscription`,
      RequestMethods.GET,
      Requests.getDefaultUserHeaders()
    ).then(({ data }) => {
      setSubscription(data.subscription);
    });
  }, [id]);

  const isSubscribed =
    subscription !== null &&
    Object.keys(subscription).length > 0 &&
    subscription.state === "ACTIVE";

  const toggleSubscription = () => {
    Requests.sendGenericRequest(
      `/files/${id}/subscription`,
      isSubscribed ? RequestMethods.DELETE : RequestMethods.POST,
      Requests.getDefaultUserHeaders()
    ).then(() => {
      SnackbarUtils.alertOk({
        id: isSubscribed
          ? "unsubscribedNotificationsForFile"
          : "subscribedNotificationsForFile",
        name
      });
    });
    ContextMenuActions.hideMenu();
  };

  return (
    <MenuItem
      onClick={toggleSubscription}
      id="contextMenuSubscription"
      caption={isSubscribed ? "turnOffNotifications" : "turnOnNotifications"}
      image={isSubscribed ? unsubscribeSVG : subscribeSVG}
      dataComponent="email-notifications"
      isLoading={subscription === null}
    />
  );
}
SubscriptionControl.propTypes = {
  id: propTypes.string.isRequired,
  name: propTypes.string.isRequired
};
