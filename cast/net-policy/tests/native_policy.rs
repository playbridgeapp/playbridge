use playbridge_net_policy::NetworkPolicy;

#[test]
fn native_dns_pin_checks_every_answer_and_the_connected_peer() {
    let value = "https://media.example.com/video";
    let url = url::Url::parse(value).unwrap();
    let mut policy = NetworkPolicy::for_registered_media(value).unwrap();
    policy
        .approve_native_addresses(&url, &["10.0.0.1:443".parse().unwrap()])
        .unwrap();
    assert!(policy
        .validate_destination(value, &["10.0.0.1".parse().unwrap()])
        .is_ok());
    for answers in [
        vec![],
        vec!["10.0.0.2".parse().unwrap()],
        vec!["8.8.8.8".parse().unwrap()],
        vec!["10.0.0.1".parse().unwrap(), "10.0.0.2".parse().unwrap()],
    ] {
        assert!(policy.validate_destination(value, &answers).is_err());
    }
    // Neither another scheme nor another port inherits native hostname trust.
    for target in [
        "http://media.example.com/video",
        "https://media.example.com:8443/video",
    ] {
        assert!(policy
            .validate_destination(target, &["10.0.0.1".parse().unwrap()])
            .is_err());
    }
}

#[test]
fn native_loopback_and_lan_trust_keep_their_distinct_scopes() {
    let loopback = NetworkPolicy::for_registered_media("http://127.0.0.1:8080/video").unwrap();
    assert!(loopback
        .validate_destination(
            "http://127.0.0.1:8080/segment",
            &["127.0.0.1".parse().unwrap()]
        )
        .is_ok());
    for target in [
        "http://127.0.0.1:9000/segment",
        "https://127.0.0.1:8080/segment",
    ] {
        assert!(loopback
            .validate_destination(target, &["127.0.0.1".parse().unwrap()])
            .is_err());
    }

    let lan = NetworkPolicy::for_registered_media("http://10.0.0.1:8080/video").unwrap();
    assert!(lan
        .validate_destination(
            "https://10.0.0.1:9000/segment",
            &["10.0.0.1".parse().unwrap()]
        )
        .is_ok());
    assert!(lan
        .validate_destination(
            "http://10.0.0.2:8080/segment",
            &["10.0.0.2".parse().unwrap()]
        )
        .is_err());

    let page = NetworkPolicy::new(vec!["http://127.0.0.1:8080".into()]).unwrap();
    assert!(page
        .validate_destination(
            "http://127.0.0.1:8080/video",
            &["127.0.0.1".parse().unwrap()]
        )
        .is_err());
}
