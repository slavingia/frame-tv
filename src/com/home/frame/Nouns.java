package com.home.frame;

import java.util.LinkedHashMap;
import java.util.Map;

/** Hand-picked, kid-safe Wikimedia Commons photos per word ("Alexa, turn on dog"). {file, caption} */
final class Nouns {
    static final Map<String, String[][]> PHOTOS = new LinkedHashMap<>();

    private static void put(String noun, String... fileCaption) {
        String[][] p = new String[fileCaption.length / 2][];
        for (int i = 0; i < p.length; i++) p[i] = new String[]{fileCaption[i * 2], fileCaption[i * 2 + 1]};
        PHOTOS.put(noun, p);
    }

    static {
        put("duck",
                "2015 Kaczka krzyżowka w wodzie (samiec).jpg", "Mallard",
                "Aix galericulata (Linnaeus 1758).jpg", "Mandarin duck",
                "Anas platyrhynchos male female.jpg", "Mallards",
                "Anas rubripes PM3.jpg", "American black duck",
                "Aythya novaeseelandiae, Lake Victoria, Christchurch, New Zealand.jpg", "New Zealand scaup",
                "Aythya nyroca at Martin Mere 1.jpg", "Ferruginous duck",
                "Dendrocygna bicolor wilhelma.jpg", "Fulvous whistling duck",
                "Dendrocygne fauve (Dendrocygna bicolor).jpg", "Fulvous whistling duck",
                "Anas platyrhynchos - Weiherwald - Karlsruhe 05.jpg", "Mallard");
        put("dog",
                "Golden_Retriever_Dukedestiny01_drvd.jpg", "Golden retriever",
                "Labrador_on_Quantock_(2175262184).jpg", "Labrador retriever",
                "Welsh_Pembroke_Corgi.jpg", "Pembroke Welsh corgi",
                "닥스훈트(단모종)_(Dachshund_(Short)).jpg", "Dachshund");
        put("cat",
                "Siam_lilacpoint.jpg", "Siamese cat",
                "Juvenile_Ragdoll.jpg", "Ragdoll kitten",
                "Mâle_Black_Silver_Blotched_Tabby.jpeg", "Tabby cat");
        put("cow",
                "Cow_(Fleckvieh_breed)_Oeschinensee_Slaunger_2009-07-07.jpg", "Fleckvieh cow",
                "Holstein_Cow_in_Montérégie,_Quebec.jpg", "Holstein cow",
                "Cow_on_Pupers.jpg", "Cow");
        put("horse",
                "Gracie-rhs2005.jpg", "Horse",
                "Standingarabianfoalone.jpg", "Arabian foal");
        put("sheep",
                "Flock_of_sheep.jpg", "Sheep",
                "251017_Rokkosan_Pasture_Kobe_Japan07s3.jpg", "Sheep");
        put("goat", "Hausziege_04.jpg", "Goat");
        put("pig", "Two_Kunekune_Pigs.jpg", "Kunekune pigs");
        put("chicken", "Male_and_female_chicken_sitting_together.jpg", "Chickens");
        put("fish",
                "Gold_fish1.jpg", "Goldfish",
                "Amphiprion_ocellaris_(Clown_anemonefish)_by_Nick_Hobgood.jpg", "Clownfish");
        put("bird",
                "Male_northern_cardinal_in_Central_Park_(52612).jpg", "Cardinal, Central Park",
                "American_robin_(71307).jpg", "American robin",
                "Blue_jay_in_PP_(30960).jpg", "Blue jay, Prospect Park");
        put("frog",
                "Red-eyed_Leaf_Frog_(49661076226).jpg", "Red-eyed tree frog",
                "Red-eyed_Tree_Frog_(Agalychnis_callidryas)_1.png", "Red-eyed tree frog");
        put("bear",
                "2010-kodiak-bear-1.jpg", "Kodiak bear",
                "Polar_Bear_-_Alaska_(cropped).jpg", "Polar bear");
        put("lion", "020_The_lion_king_Snyggve_in_the_Serengeti_National_Park_Photo_by_Giles_Laurent.jpg", "Lion, Serengeti");
        put("tiger", "Bengal_tiger_(Panthera_tigris_tigris)_female_3_crop.jpg", "Bengal tiger");
        put("elephant",
                "Elephas_maximus_(Bandipur).jpg", "Asian elephant",
                "178_Male_African_bush_elephant_in_Etosha_National_Park_Photo_by_Giles_Laurent.jpg", "African elephant");
        put("giraffe", "Giraffe_Mikumi_National_Park.jpg", "Giraffe");
        put("zebra",
                "Equus_quagga_burchellii_-_Etosha,_2014.jpg", "Zebra",
                "Plains_Zebra_Equus_quagga_cropped.jpg", "Plains zebra");
        put("bunny",
                "Oryctolagus_cuniculus_-_euqirneto_-_419737670_(cropped).jpeg", "Rabbit",
                "Oryctolagus_cuniculus_Rcdo.jpg", "Rabbit");
        put("butterfly",
                "Fesoj_-_Papilio_machaon_(by).jpg", "Swallowtail",
                "Monarch_Butterfly_Danaus_plexippus_Male_2664px.jpg", "Monarch");
        put("bee",
                "Apis_mellifera_Western_honey_bee.jpg", "Honey bee",
                "Bombus_lapidarius_-_Melilotus_officinalis_-_Tallinn.jpg", "Bumblebee");
        put("penguin",
                "South_Shetland-2016-Deception_Island–Chinstrap_penguin_(Pygoscelis_antarctica)_04.jpg", "Chinstrap penguin",
                "Aptenodytes_forsteri_-Snow_Hill_Island,_Antarctica_-adults_and_juvenile-8.jpg", "Emperor penguins");
        put("squirrel",
                "EasternGraySquirrel_GAm.jpg", "Gray squirrel",
                "Squirrel_posing.jpg", "Squirrel");
        put("owl",
                "American_Barn_Owl_(Tyto_furcata_guatemalae),_Orange_Walk.jpg", "Barn owl",
                "Bubo_virginianus_06.jpg", "Great horned owl",
                "Southern_burrowing_owl_(Athene_cunicularia_cunicularia)_Colonia.jpg", "Burrowing owl");
        put("turtle",
                "Chelonia_mydas_is_going_for_the_air_edit.jpg", "Green sea turtle",
                "Green_sea_turtle_(Chelonia_mydas)_Moorea.jpg", "Green sea turtle");
        put("panda", "Grosser_Panda.JPG", "Giant panda");
        put("monkey",
                "Capuchin_Costa_Rica.jpg", "Capuchin monkey",
                "Japanese_Snow_Monkey_(Macaque)_Mother_Grooms_Her_Young.jpg", "Snow monkeys");
        put("whale", "Humpback_whale_breaching_off_Cabo_San_Lucas.jpg", "Humpback whale");
        put("dolphin", "Tursiops_truncatus_01-cropped.jpg", "Bottlenose dolphin");
        put("car", "2005_Toyota_Corolla_(ZZE122R_5Y)_Ascent_sedan_01.jpg", "Car");
        put("truck", "2001_Ford_F-250_Super_Duty_XL_regular_cab_flatbed,_front_left,_05-17-2025.jpg", "Pickup truck");
        put("fire truck", "Dublin_Fire_Brigade_Pump_Ladder_D32.jpg", "Fire engine");
        put("bus",
                "IC_CE_school_bus_(shortened_chassis).jpg", "School bus",
                "LTZ1328-19-20241030-160332.jpg", "Double-decker bus");
        put("train",
                "Number_4468_Mallard_in_York.jpg", "Steam locomotive Mallard",
                "Поезд_на_фоне_горы_Шатрище._Воронежская_область.jpg", "Train");
        put("airplane", "United_Airlines_Boeing_777-200_Meulemans.jpg", "Airplane");
        put("boat", "Motorboat_at_Kankaria_lake.JPG", "Motorboat");
        put("tractor", "Ford_8N.jpg", "Tractor");
        put("moon",
                "FullMoon2020.jpg", "Full moon",
                "FullMoon2010.jpg", "Full moon");
        put("sun", "The_Sun_in_white_light.jpg", "The Sun");
        put("rainbow", "Double-alaskan-rainbow.jpg", "Double rainbow");
        put("tree", "Usamljeni_jasen_-_panoramio_(cropped).jpg", "Ash tree");
        put("flower",
                "Sunflower_sky_backdrop.jpg", "Sunflower",
                "צבעונים.JPG", "Tulips");
        put("apple", "Pink_lady_and_cross_section.jpg", "Apple");
        put("banana", "Cavendish_Banana_DS.jpg", "Bananas");
        put("strawberry", "Garden_strawberry_(Fragaria_×_ananassa)_single2.jpg", "Strawberry");
        put("rice",
                "Meshi_001.jpg", "A bowl of rice",
                "小料理バルさくら_特製おにぎり.jpg", "Onigiri (rice balls)");
        put("cookie", "Choco_chip_cookie.png", "Chocolate chip cookie");
        put("balloon",
                "2006_Ojiya_balloon_festival_011.jpg", "Hot air balloon",
                "Congrats_bqt.jpg", "Balloons");
        put("bubbles", "Reflection_in_a_soap_bubble_edit.jpg", "Soap bubble");
        put("snowman", "Snowman_in_Indiana_2014.jpg", "Snowman");
    }

    private Nouns() {}
}
