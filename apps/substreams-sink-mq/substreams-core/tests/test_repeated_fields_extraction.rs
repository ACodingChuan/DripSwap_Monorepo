// Test RepeatedFields individual message extraction
use std::collections::HashMap;
use substreams_core::protobuf::{FieldValue, ParsedMessage};
use substreams_core::routing::dynamic::{
    DynamicFanoutConfig, DynamicMessageRouter, FanoutStrategy,
};

#[test]
fn test_repeated_fields_extracts_individual_messages() {
    // Create test Events message with repeated token_approvals field
    let mut events_fields = HashMap::new();

    // Create array of TokenApproval messages
    let mut token_approval_1_fields = HashMap::new();
    token_approval_1_fields.insert(
        "tx_hash".to_string(),
        FieldValue::String("0x123".to_string()),
    );
    token_approval_1_fields.insert("event_index".to_string(), FieldValue::Uint32(1));
    token_approval_1_fields.insert(
        "id".to_string(),
        FieldValue::String("approval_1".to_string()),
    );

    let mut token_approval_2_fields = HashMap::new();
    token_approval_2_fields.insert(
        "tx_hash".to_string(),
        FieldValue::String("0x456".to_string()),
    );
    token_approval_2_fields.insert("event_index".to_string(), FieldValue::Uint32(2));
    token_approval_2_fields.insert(
        "id".to_string(),
        FieldValue::String("approval_2".to_string()),
    );

    let token_approval_1 = ParsedMessage {
        type_url: "TokenApproval".to_string(),
        fields: token_approval_1_fields,
    };

    let token_approval_2 = ParsedMessage {
        type_url: "TokenApproval".to_string(),
        fields: token_approval_2_fields,
    };

    // Create array with 2 TokenApproval messages
    let token_approvals_array = vec![
        FieldValue::Message(Box::new(token_approval_1)),
        FieldValue::Message(Box::new(token_approval_2)),
    ];

    events_fields.insert(
        "token_approvals".to_string(),
        FieldValue::Array(token_approvals_array),
    );

    let events_message = ParsedMessage {
        type_url: "Events".to_string(),
        fields: events_fields,
    };

    // Create fanout config for RepeatedFields strategy
    let mut topics = HashMap::new();
    topics.insert("token_approvals".to_string(), "token_approvals".to_string());

    let config = DynamicFanoutConfig {
        default_topic: "fallback".to_string(),
        topic_prefix: Some("contract".to_string()),
        fanout_strategy: FanoutStrategy::RepeatedFields {
            message_type: "Events".to_string(),
            repeated_fields: vec!["token_approvals".to_string()],
        },
        topics,
        discriminator: None,
    };

    // Create router and route the message
    let router = DynamicMessageRouter::new(config);
    let routings = router
        .route_message(&events_message, 12345)
        .expect("Routing should succeed");

    // Should create 2 separate routings (one for each TokenApproval)
    assert_eq!(
        routings.len(),
        2,
        "Should create 2 routings for 2 TokenApproval messages"
    );

    // Verify each routing
    for (i, routing) in routings.iter().enumerate() {
        assert_eq!(routing.topic_name, "contract_token_approvals");
        assert_eq!(routing.message_data.message_type, "token_approvals");

        // Serialize to JSON to check flattened structure
        let json_string = serde_json::to_string(&routing.message_data)
            .expect("JSON serialization should succeed");
        let json_value: serde_json::Value =
            serde_json::from_str(&json_string).expect("JSON parsing should succeed");
        let obj = json_value.as_object().expect("Should be JSON object");

        // Should have flattened fields at top level, not nested in array
        assert!(
            obj.contains_key("tx_hash"),
            "Should have flattened tx_hash field"
        );
        assert!(
            obj.contains_key("event_index"),
            "Should have flattened event_index field"
        );
        assert!(obj.contains_key("id"), "Should have flattened id field");

        // Should NOT have the array field
        assert!(
            !obj.contains_key("token_approvals"),
            "Should not have nested token_approvals array"
        );

        // Verify values are correct for each message
        if i == 0 {
            assert_eq!(obj["tx_hash"].as_str().unwrap(), "0x123");
            assert_eq!(obj["event_index"].as_u64().unwrap(), 1);
            assert_eq!(obj["id"].as_str().unwrap(), "approval_1");
        } else {
            assert_eq!(obj["tx_hash"].as_str().unwrap(), "0x456");
            assert_eq!(obj["event_index"].as_u64().unwrap(), 2);
            assert_eq!(obj["id"].as_str().unwrap(), "approval_2");
        }

        println!("✅ Routing {}: {}", i + 1, json_string);
    }

    println!(
        "✅ RepeatedFields correctly extracts {} individual messages!",
        routings.len()
    );
}

#[test]
fn test_repeated_fields_handles_empty_array() {
    // Test that empty arrays don't create routings
    let mut events_fields = HashMap::new();
    events_fields.insert("token_approvals".to_string(), FieldValue::Array(vec![]));

    let events_message = ParsedMessage {
        type_url: "Events".to_string(),
        fields: events_fields,
    };

    let mut topics = HashMap::new();
    topics.insert("token_approvals".to_string(), "token_approvals".to_string());

    let config = DynamicFanoutConfig {
        default_topic: "fallback".to_string(),
        topic_prefix: Some("contract".to_string()),
        fanout_strategy: FanoutStrategy::RepeatedFields {
            message_type: "Events".to_string(),
            repeated_fields: vec!["token_approvals".to_string()],
        },
        topics,
        discriminator: None,
    };

    let router = DynamicMessageRouter::new(config);
    let routings = router
        .route_message(&events_message, 12345)
        .expect("Routing should succeed");

    // Should route to default topic when no repeated fields have data
    assert_eq!(routings.len(), 1);
    assert_eq!(routings[0].topic_name, "contract_fallback");

    println!("✅ Empty arrays correctly route to default topic");
}
